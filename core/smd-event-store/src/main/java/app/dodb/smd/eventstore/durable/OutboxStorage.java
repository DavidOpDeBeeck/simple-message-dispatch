package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.SerializedEvent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

class OutboxStorage {

    private final ConnectionProvider connectionProvider;

    OutboxStorage(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    void append(String outbox, SerializedEvent event) {
        connectionProvider.doWithConnection(connection -> {
            var position = event.subjectId().map(subjectId -> nextPosition(connection, outbox, subjectId));
            try (var stmt = connection.prepareStatement("""
                INSERT INTO smd_outbox
                    (outbox, message_id, subject_id, position, event_type, serialized_payload, serialized_metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                stmt.setString(1, outbox);
                stmt.setObject(2, event.messageId().value());
                stmt.setString(3, event.subjectId().orElse(null));
                if (position.isPresent()) {
                    stmt.setLong(4, position.get());
                } else {
                    stmt.setNull(4, Types.BIGINT);
                }
                stmt.setString(5, event.eventType());
                stmt.setBytes(6, event.serializedPayload());
                stmt.setBytes(7, event.serializedMetadata());
                stmt.setTimestamp(8, Timestamp.from(event.createdAt()));
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to append event to outbox '" + outbox + "': messageId=" + event.messageId().value(), e);
            }
        });
    }

    // The sequence row stays locked until the publishing transaction ends, so positions of one subject follow commit order.
    private long nextPosition(Connection connection, String outbox, String subjectId) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_outbox_sequence (outbox, subject_id, last_position)
            VALUES (?, ?, 1)
            ON CONFLICT (outbox, subject_id) DO UPDATE SET last_position = smd_outbox_sequence.last_position + 1
            RETURNING last_position
            """)) {
            stmt.setString(1, outbox);
            stmt.setString(2, subjectId);
            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to assign an outbox position: outbox=" + outbox + ", subjectId=" + subjectId, e);
        }
    }

    // Locks the claimed row until the caller's transaction ends. Earlier unsent entries of the same subject keep later ones back.
    Optional<OutboxEntry> claimNext(String outbox) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT o.id, o.message_id, o.subject_id, o.position, o.event_type, o.serialized_payload, o.serialized_metadata, o.created_at, o.attempts
                FROM smd_outbox o
                WHERE o.outbox = ?
                  AND o.sent_at IS NULL
                  AND o.next_attempt_at <= clock_timestamp()
                  AND NOT EXISTS (SELECT 1
                                  FROM smd_outbox earlier
                                  WHERE earlier.outbox = o.outbox
                                    AND earlier.subject_id = o.subject_id
                                    AND earlier.position < o.position
                                    AND earlier.sent_at IS NULL)
                ORDER BY o.id
                LIMIT 1
                FOR UPDATE OF o SKIP LOCKED
                """)) {
                stmt.setString(1, outbox);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<OutboxEntry>empty();
                    }
                    return Optional.of(mapEntry(outbox, rs));
                }
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to claim the next entry of outbox '" + outbox + "'", e);
            }
        });
    }

    void markSent(String outbox, long id) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                UPDATE smd_outbox
                SET sent_at = clock_timestamp(), last_error = NULL
                WHERE id = ? AND sent_at IS NULL
                """)) {
                stmt.setLong(1, id);
                requireOneRow(stmt.executeUpdate(), outbox, id);
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to mark outbox entry as sent: outbox=" + outbox + ", id=" + id, e);
            }
        });
    }

    void recordFailure(String outbox, OutboxEntry entry, Duration retryDelay, Exception failure) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                UPDATE smd_outbox
                SET attempts = attempts + 1,
                    next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                    last_error = ?
                WHERE id = ? AND sent_at IS NULL AND attempts = ?
                """)) {
                stmt.setLong(1, retryDelay.toMillis());
                stmt.setString(2, failure.toString());
                stmt.setLong(3, entry.id());
                stmt.setInt(4, entry.attempts());
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new DurableStorageException("Failed to record an outbox send failure: outbox=" + outbox + ", id=" + entry.id(), e);
            }
        });
    }

    private static OutboxEntry mapEntry(String outbox, ResultSet rs) throws SQLException {
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
        var position = subjectId.map(ignored -> positionOf(outbox, rs));
        return new OutboxEntry(rs.getLong("id"), new TransportEvent(event, position), rs.getInt("attempts"));
    }

    private static OutboxPosition positionOf(String outbox, ResultSet rs) {
        try {
            return new OutboxPosition(outbox, rs.getLong("position"));
        } catch (SQLException e) {
            throw new DurableStorageException("Failed to read the position of an outbox entry: outbox=" + outbox, e);
        }
    }

    private static void requireOneRow(int updatedRows, String outbox, long id) {
        if (updatedRows != 1) {
            throw new IllegalStateException("Expected to update one unsent outbox entry, updated " + updatedRows + ": outbox=" + outbox + ", id=" + id);
        }
    }
}
