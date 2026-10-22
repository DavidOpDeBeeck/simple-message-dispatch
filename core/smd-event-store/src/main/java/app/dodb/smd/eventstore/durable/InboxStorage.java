package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

class InboxStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxStorage.class);

    private final ConnectionProvider connectionProvider;

    InboxStorage(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    // Stores and fans out under the inbox lock, so a group registering at the same time either sees the row or receives it here.
    void store(String inbox, TransportEvent transportEvent) {
        connectionProvider.doWithConnection(connection -> {
            lockInbox(connection, inbox);
            var id = insert(connection, inbox, transportEvent);
            if (id.isEmpty()) {
                reportNotStored(connection, inbox, transportEvent);
                return;
            }
            for (var processingGroup : registeredGroups(connection, inbox)) {
                queue(connection, inbox, processingGroup, id.getAsLong(), transportEvent);
            }
        });
    }

    // Returns false when the group was already registered.
    boolean register(String inbox, String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            lockInbox(connection, inbox);
            try (var insertGroup = connection.prepareStatement("""
                INSERT INTO smd_inbox_group (inbox, processing_group)
                VALUES (?, ?)
                ON CONFLICT (inbox, processing_group) DO NOTHING
                """);
                 var insertCursors = connection.prepareStatement("""
                     INSERT INTO smd_inbox_cursor (inbox, processing_group, origin, subject_id, next_position)
                     SELECT inbox, ?, origin, subject_id, MIN(position)
                     FROM smd_inbox
                     WHERE inbox = ? AND position IS NOT NULL
                     GROUP BY inbox, origin, subject_id
                     ON CONFLICT DO NOTHING
                     """);
                 var insertPending = connection.prepareStatement("""
                     INSERT INTO smd_inbox_pending (inbox, processing_group, id, origin, subject_id, position)
                     SELECT inbox, ?, id, origin, subject_id, position
                     FROM smd_inbox
                     WHERE inbox = ?
                     """)) {
                insertGroup.setString(1, inbox);
                insertGroup.setString(2, processingGroup);
                if (insertGroup.executeUpdate() == 0) {
                    return false;
                }

                insertCursors.setString(1, processingGroup);
                insertCursors.setString(2, inbox);
                insertCursors.executeUpdate();

                insertPending.setString(1, processingGroup);
                insertPending.setString(2, inbox);
                var queued = insertPending.executeUpdate();
                LOGGER.info("Inbox group registered: inbox={}, processingGroup={}, queuedEvents={}", inbox, processingGroup, queued);
                return true;
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to register processing group '" + processingGroup + "' on inbox '" + inbox + "'", e);
            }
        });
    }

    // Locks the claimed pending row until the caller's transaction ends. A positioned event is only claimable at its cursor's next position.
    Optional<InboxEntry> claimNext(String inbox, String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT p.id, p.attempts, i.message_id, i.origin, i.subject_id, i.position, i.event_type,
                       i.serialized_payload, i.serialized_metadata, i.created_at
                FROM smd_inbox_pending p
                JOIN smd_inbox i ON i.id = p.id
                LEFT JOIN smd_inbox_cursor c
                    ON c.inbox = p.inbox
                    AND c.processing_group = p.processing_group
                    AND c.origin = p.origin
                    AND c.subject_id = p.subject_id
                WHERE p.inbox = ?
                  AND p.processing_group = ?
                  AND p.next_attempt_at <= clock_timestamp()
                  AND (p.position IS NULL OR p.position = c.next_position)
                ORDER BY p.id
                LIMIT 1
                FOR UPDATE OF p SKIP LOCKED
                """)) {
                stmt.setString(1, inbox);
                stmt.setString(2, processingGroup);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<InboxEntry>empty();
                    }
                    return Optional.of(new InboxEntry(rs.getLong("id"), mapTransportEvent(rs), rs.getInt("attempts")));
                }
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to claim the next event of inbox '" + inbox + "' for processing group '" + processingGroup + "'", e);
            }
        });
    }

    void markDelivered(String inbox, String processingGroup, InboxEntry entry) {
        connectionProvider.doWithConnection(connection -> {
            try (var deletePending = connection.prepareStatement("""
                DELETE FROM smd_inbox_pending
                WHERE inbox = ? AND processing_group = ? AND id = ?
                """);
                 var advanceCursor = connection.prepareStatement("""
                     UPDATE smd_inbox_cursor
                     SET next_position = next_position + 1
                     WHERE inbox = ? AND processing_group = ? AND origin = ? AND subject_id = ? AND next_position = ?
                     """)) {
                deletePending.setString(1, inbox);
                deletePending.setString(2, processingGroup);
                deletePending.setLong(3, entry.id());
                requireOneRow(deletePending.executeUpdate(), "pending event", inbox, processingGroup, entry);

                if (entry.event().position().isPresent()) {
                    var position = entry.event().position().get();
                    advanceCursor.setString(1, inbox);
                    advanceCursor.setString(2, processingGroup);
                    advanceCursor.setString(3, position.outbox());
                    advanceCursor.setString(4, entry.event().event().subjectId().orElseThrow());
                    advanceCursor.setLong(5, position.position());
                    requireOneRow(advanceCursor.executeUpdate(), "cursor", inbox, processingGroup, entry);
                }
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to mark an inbox event as delivered: inbox=" + inbox + ", processingGroup=" + processingGroup
                    + ", messageId=" + entry.event().event().messageId().value(), e);
            }
        });
    }

    void recordFailure(String inbox, String processingGroup, InboxEntry entry, Duration retryDelay, Exception failure) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                UPDATE smd_inbox_pending
                SET attempts = attempts + 1,
                    next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                    last_error = ?
                WHERE inbox = ? AND processing_group = ? AND id = ? AND attempts = ?
                """)) {
                stmt.setLong(1, retryDelay.toMillis());
                stmt.setString(2, failure.toString());
                stmt.setString(3, inbox);
                stmt.setString(4, processingGroup);
                stmt.setLong(5, entry.id());
                stmt.setInt(6, entry.attempts());
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to record an inbox delivery failure: inbox=" + inbox + ", processingGroup=" + processingGroup
                    + ", messageId=" + entry.event().event().messageId().value(), e);
            }
        });
    }

    List<StalledSubject> findStalledSubjects(String inbox, String processingGroup, Duration waitingLongerThan) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT c.origin, c.subject_id, c.next_position, MIN(p.position) AS first_available_position, MIN(i.received_at) AS waiting_since
                FROM smd_inbox_cursor c
                JOIN smd_inbox_pending p
                    ON p.inbox = c.inbox
                    AND p.processing_group = c.processing_group
                    AND p.origin = c.origin
                    AND p.subject_id = c.subject_id
                JOIN smd_inbox i ON i.id = p.id
                WHERE c.inbox = ? AND c.processing_group = ?
                GROUP BY c.origin, c.subject_id, c.next_position
                HAVING MIN(p.position) > c.next_position
                   AND MIN(i.received_at) < clock_timestamp() - (? * interval '1 millisecond')
                """)) {
                stmt.setString(1, inbox);
                stmt.setString(2, processingGroup);
                stmt.setLong(3, waitingLongerThan.toMillis());
                try (var rs = stmt.executeQuery()) {
                    var stalledSubjects = new ArrayList<StalledSubject>();
                    while (rs.next()) {
                        stalledSubjects.add(new StalledSubject(
                            rs.getString("origin"),
                            rs.getString("subject_id"),
                            rs.getLong("next_position"),
                            rs.getLong("first_available_position"),
                            rs.getTimestamp("waiting_since").toInstant()
                        ));
                    }
                    return stalledSubjects;
                }
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to find stalled subjects of inbox '" + inbox + "' for processing group '" + processingGroup + "'", e);
            }
        });
    }

    private static void lockInbox(Connection connection, String inbox) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_advisory_xact_lock(hashtext('smd_inbox'), hashtext(?))
            """)) {
            stmt.setString(1, inbox);
            stmt.executeQuery().close();
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to lock inbox '" + inbox + "'", e);
        }
    }

    private static OptionalLong insert(Connection connection, String inbox, TransportEvent transportEvent) {
        var event = transportEvent.event();
        var position = transportEvent.position();
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_inbox
                (inbox, message_id, origin, subject_id, position, event_type, serialized_payload, serialized_metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id
            """)) {
            stmt.setString(1, inbox);
            stmt.setObject(2, event.messageId().value());
            stmt.setString(3, position.map(OutboxPosition::outbox).orElse(null));
            stmt.setString(4, event.subjectId().orElse(null));
            if (position.isPresent()) {
                stmt.setLong(5, position.get().position());
            } else {
                stmt.setNull(5, Types.BIGINT);
            }
            stmt.setString(6, event.eventType());
            stmt.setBytes(7, event.serializedPayload());
            stmt.setBytes(8, event.serializedMetadata());
            stmt.setTimestamp(9, Timestamp.from(event.createdAt()));
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to store event in inbox '" + inbox + "': messageId=" + event.messageId().value(), e);
        }
    }

    // A message ID seen before is a redelivery. Another message at a stored position means two outboxes share a name.
    private static void reportNotStored(Connection connection, String inbox, TransportEvent transportEvent) {
        var messageId = transportEvent.event().messageId();
        try (var stmt = connection.prepareStatement("""
            SELECT message_id FROM smd_inbox WHERE inbox = ? AND message_id = ?
            """)) {
            stmt.setString(1, inbox);
            stmt.setObject(2, messageId.value());
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    LOGGER.debug("Duplicate inbox event ignored: inbox={}, messageId={}", inbox, messageId.value());
                    return;
                }
            }
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to look up inbox event: inbox=" + inbox + ", messageId=" + messageId.value(), e);
        }
        var position = transportEvent.position().orElseThrow();
        LOGGER.error("Inbox event dropped: another message already holds its position, so two outboxes probably share the name '{}': inbox={}, subjectId={}, position={}, "
                + "droppedMessageId={}, storedMessageId={}",
            position.outbox(), inbox, transportEvent.event().subjectId().orElse(null), position.position(), messageId.value(),
            storedMessageIdAt(connection, inbox, transportEvent).orElse(null));
    }

    private static Optional<UUID> storedMessageIdAt(Connection connection, String inbox, TransportEvent transportEvent) {
        var position = transportEvent.position().orElseThrow();
        try (var stmt = connection.prepareStatement("""
            SELECT message_id FROM smd_inbox WHERE inbox = ? AND origin = ? AND subject_id = ? AND position = ?
            """)) {
            stmt.setString(1, inbox);
            stmt.setString(2, position.outbox());
            stmt.setString(3, transportEvent.event().subjectId().orElseThrow());
            stmt.setLong(4, position.position());
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to look up the inbox event at outbox '" + position.outbox() + "' position " + position.position(), e);
        }
    }

    private static List<String> registeredGroups(Connection connection, String inbox) {
        try (var stmt = connection.prepareStatement("""
            SELECT processing_group FROM smd_inbox_group WHERE inbox = ? ORDER BY processing_group
            """)) {
            stmt.setString(1, inbox);
            try (var rs = stmt.executeQuery()) {
                var groups = new ArrayList<String>();
                while (rs.next()) {
                    groups.add(rs.getString(1));
                }
                return groups;
            }
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to read the processing groups of inbox '" + inbox + "'", e);
        }
    }

    // A subject's cursor starts at the first position the group sees. A lower position arriving later is never delivered.
    private static void queue(Connection connection, String inbox, String processingGroup, long id, TransportEvent transportEvent) {
        var position = transportEvent.position();
        if (position.isPresent()) {
            var subjectId = transportEvent.event().subjectId().orElseThrow();
            var nextPosition = startCursor(connection, inbox, processingGroup, position.get(), subjectId);
            if (position.get().position() < nextPosition) {
                LOGGER.error("Inbox event arrived behind its subject's cursor and will not be delivered: inbox={}, processingGroup={}, origin={}, subjectId={}, "
                        + "position={}, nextPosition={}, messageId={}",
                    inbox, processingGroup, position.get().outbox(), subjectId, position.get().position(), nextPosition, transportEvent.event().messageId().value());
                return;
            }
        }
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_inbox_pending (inbox, processing_group, id, origin, subject_id, position)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            stmt.setString(1, inbox);
            stmt.setString(2, processingGroup);
            stmt.setLong(3, id);
            stmt.setString(4, position.map(OutboxPosition::outbox).orElse(null));
            stmt.setString(5, transportEvent.event().subjectId().orElse(null));
            if (position.isPresent()) {
                stmt.setLong(6, position.get().position());
            } else {
                stmt.setNull(6, Types.BIGINT);
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to queue inbox event for processing group '" + processingGroup + "': inbox=" + inbox
                + ", messageId=" + transportEvent.event().messageId().value(), e);
        }
    }

    private static long startCursor(Connection connection, String inbox, String processingGroup, OutboxPosition position, String subjectId) {
        try (var insert = connection.prepareStatement("""
            INSERT INTO smd_inbox_cursor (inbox, processing_group, origin, subject_id, next_position)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """);
             var select = connection.prepareStatement("""
                 SELECT next_position FROM smd_inbox_cursor
                 WHERE inbox = ? AND processing_group = ? AND origin = ? AND subject_id = ?
                 """)) {
            insert.setString(1, inbox);
            insert.setString(2, processingGroup);
            insert.setString(3, position.outbox());
            insert.setString(4, subjectId);
            insert.setLong(5, position.position());
            insert.executeUpdate();

            select.setString(1, inbox);
            select.setString(2, processingGroup);
            select.setString(3, position.outbox());
            select.setString(4, subjectId);
            try (var rs = select.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to start the cursor of subject '" + subjectId + "' from outbox '" + position.outbox()
                + "': inbox=" + inbox + ", processingGroup=" + processingGroup, e);
        }
    }

    private static TransportEvent mapTransportEvent(ResultSet rs) throws SQLException {
        var subjectId = Optional.ofNullable(rs.getString("subject_id"));
        var event = new SerializedEvent(
            new MessageId(rs.getObject("message_id", UUID.class)),
            null,
            subjectId,
            rs.getString("event_type"),
            rs.getBytes("serialized_payload"),
            rs.getBytes("serialized_metadata"),
            rs.getTimestamp("created_at").toInstant()
        );
        var origin = rs.getString("origin");
        var position = rs.getLong("position");
        return new TransportEvent(event, rs.wasNull() ? Optional.empty() : Optional.of(new OutboxPosition(origin, position)));
    }

    private static void requireOneRow(int updatedRows, String row, String inbox, String processingGroup, InboxEntry entry) {
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected to update one inbox " + row + ", updated " + updatedRows + ": inbox=" + inbox
                + ", processingGroup=" + processingGroup + ", messageId=" + entry.event().event().messageId().value());
        }
    }

    record StalledSubject(String origin, String subjectId, long nextPosition, long firstAvailablePosition, Instant waitingSince) {

        StalledSubject {
            requireNonNull(origin);
            requireNonNull(subjectId);
            requireNonNull(waitingSince);
        }
    }
}
