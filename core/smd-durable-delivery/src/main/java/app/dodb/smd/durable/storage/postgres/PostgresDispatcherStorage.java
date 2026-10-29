package app.dodb.smd.durable.storage.postgres;

import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.durable.storage.DispatcherStorageException;
import app.dodb.smd.durable.storage.PendingDelivery;
import app.dodb.smd.serialization.SerializedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public class PostgresDispatcherStorage implements DispatcherStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresDispatcherStorage.class);

    private final ConnectionProvider connectionProvider;

    public PostgresDispatcherStorage(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public void store(String dispatcher, SerializedEvent event) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                INSERT INTO smd_dispatcher_queue
                    (dispatcher, subscriber, lane, message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at)
                SELECT s.dispatcher, s.subscriber, mod(mod(?, d.lane_count) + d.lane_count, d.lane_count), ?, ?, ?, ?, ?, ?
                FROM smd_dispatcher_subscriber s
                JOIN smd_dispatcher d ON d.dispatcher = s.dispatcher
                WHERE s.dispatcher = ?
                ON CONFLICT (dispatcher, subscriber, message_id) DO NOTHING
                """)) {
                stmt.setInt(1, laneKey(event));
                stmt.setObject(2, event.messageId().value());
                stmt.setString(3, event.subjectId().orElse(null));
                stmt.setString(4, event.eventType());
                stmt.setBytes(5, event.serializedPayload());
                stmt.setBytes(6, event.serializedMetadata());
                stmt.setTimestamp(7, Timestamp.from(event.createdAt()));
                stmt.setString(8, dispatcher);
                if (stmt.executeUpdate() == 0) {
                    LOGGER.debug("Event queued for no subscriber, as a duplicate or for lack of subscribers: dispatcher={}, messageId={}",
                        dispatcher, event.messageId().value());
                }
            } catch (SQLException e) {
                throw new DispatcherStorageException("Failed to store event in dispatcher '" + dispatcher + "': messageId=" + event.messageId().value(), e);
            }
        });
    }

    @Override
    public int registerDispatcher(String dispatcher, int laneCount) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                INSERT INTO smd_dispatcher (dispatcher, lane_count)
                VALUES (?, ?)
                ON CONFLICT (dispatcher) DO NOTHING
                """)) {
                stmt.setString(1, dispatcher);
                stmt.setInt(2, laneCount);
                if (stmt.executeUpdate() == 1) {
                    LOGGER.info("Dispatcher registered: dispatcher={}, lanes={}", dispatcher, laneCount);
                }
            } catch (SQLException e) {
                throw new DispatcherStorageException("Failed to register dispatcher '" + dispatcher + "'", e);
            }
            return laneCountOf(connection, dispatcher);
        });
    }

    @Override
    public void registerSubscriber(String dispatcher, String subscriber) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                INSERT INTO smd_dispatcher_subscriber (dispatcher, subscriber)
                VALUES (?, ?)
                ON CONFLICT (dispatcher, subscriber) DO NOTHING
                """)) {
                stmt.setString(1, dispatcher);
                stmt.setString(2, subscriber);
                if (stmt.executeUpdate() == 1) {
                    LOGGER.info("Subscriber registered: dispatcher={}, subscriber={}", dispatcher, subscriber);
                }
            } catch (SQLException e) {
                throw new DispatcherStorageException("Failed to register subscriber '" + subscriber + "' on dispatcher '" + dispatcher + "'", e);
            }
            createLanes(connection, dispatcher, subscriber);
        });
    }

    @Override
    public List<PendingDelivery> claimBatch(String dispatcher, String subscriber, int batchSize) {
        return connectionProvider.doWithConnection(connection -> {
            var deliveries = new ArrayList<PendingDelivery>();
            var claimedLanes = new ArrayList<Integer>();
            while (deliveries.size() < batchSize) {
                var lane = lockNextLane(connection, dispatcher, subscriber, claimedLanes);
                if (lane.isEmpty()) {
                    return deliveries;
                }
                claimedLanes.add(lane.getAsInt());
                deliveries.addAll(readDueEvents(connection, dispatcher, subscriber, lane.getAsInt(), batchSize - deliveries.size()));
            }
            return deliveries;
        });
    }

    @Override
    public void markDelivered(String dispatcher, String subscriber, PendingDelivery delivery) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("DELETE FROM smd_dispatcher_queue WHERE id = ?")) {
                stmt.setLong(1, delivery.id());
                if (stmt.executeUpdate() != 1) {
                    throw new IllegalStateException("Expected to delete one queued event, deleted none: dispatcher=" + dispatcher
                        + ", subscriber=" + subscriber + ", messageId=" + delivery.event().messageId().value());
                }
            } catch (SQLException e) {
                throw new DispatcherStorageException("Failed to mark an event as delivered: dispatcher=" + dispatcher + ", subscriber=" + subscriber
                    + ", messageId=" + delivery.event().messageId().value(), e);
            }
        });
    }

    @Override
    public void recordFailure(String dispatcher, String subscriber, PendingDelivery delivery, Duration retryDelay, Throwable failure) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                UPDATE smd_dispatcher_queue
                SET attempts = attempts + 1,
                    next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                    last_error = ?
                WHERE id = ? AND attempts = ?
                """)) {
                stmt.setLong(1, retryDelay.toMillis());
                stmt.setString(2, failure.toString());
                stmt.setLong(3, delivery.id());
                stmt.setInt(4, delivery.attempts());
                if (stmt.executeUpdate() == 0) {
                    LOGGER.debug("Delivery failure already recorded or event already delivered: dispatcher={}, subscriber={}, messageId={}",
                        dispatcher, subscriber, delivery.event().messageId().value());
                }
            } catch (SQLException e) {
                throw new DispatcherStorageException("Failed to record a delivery failure: dispatcher=" + dispatcher + ", subscriber=" + subscriber
                    + ", messageId=" + delivery.event().messageId().value(), e);
            }
        });
    }

    private static int laneCountOf(Connection connection, String dispatcher) {
        try (var stmt = connection.prepareStatement("SELECT lane_count FROM smd_dispatcher WHERE dispatcher = ?")) {
            stmt.setString(1, dispatcher);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Dispatcher '" + dispatcher + "' is not registered");
                }
                return rs.getInt("lane_count");
            }
        } catch (SQLException e) {
            throw new DispatcherStorageException("Failed to read the lane count of dispatcher '" + dispatcher + "'", e);
        }
    }

    private static void createLanes(Connection connection, String dispatcher, String subscriber) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_dispatcher_lane (dispatcher, subscriber, lane)
            SELECT dispatcher, ?, generate_series(0, lane_count - 1)
            FROM smd_dispatcher
            WHERE dispatcher = ?
            ON CONFLICT (dispatcher, subscriber, lane) DO NOTHING
            """)) {
            stmt.setString(1, subscriber);
            stmt.setString(2, dispatcher);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DispatcherStorageException("Failed to create the lanes of subscriber '" + subscriber + "' on dispatcher '" + dispatcher + "'", e);
        }
    }

    private static int laneKey(SerializedEvent event) {
        return event.subjectId().orElseGet(() -> event.messageId().value().toString()).hashCode();
    }

    private static OptionalInt lockNextLane(Connection connection, String dispatcher, String subscriber, List<Integer> claimedLanes) {
        try (var stmt = connection.prepareStatement("""
            SELECT l.lane
            FROM smd_dispatcher_lane l
            CROSS JOIN LATERAL (SELECT q.id, q.next_attempt_at
                                FROM smd_dispatcher_queue q
                                WHERE q.dispatcher = l.dispatcher AND q.subscriber = l.subscriber AND q.lane = l.lane
                                ORDER BY q.id
                                LIMIT 1) head
            WHERE l.dispatcher = ? AND l.subscriber = ? AND l.lane <> ALL (?) AND head.next_attempt_at <= clock_timestamp()
            ORDER BY head.id
            LIMIT 1
            FOR UPDATE OF l SKIP LOCKED
            """)) {
            stmt.setString(1, dispatcher);
            stmt.setString(2, subscriber);
            stmt.setArray(3, connection.createArrayOf("integer", claimedLanes.toArray()));
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt("lane")) : OptionalInt.empty();
            }
        } catch (SQLException e) {
            throw new DispatcherStorageException("Failed to claim a lane of dispatcher '" + dispatcher + "' for subscriber '" + subscriber + "'", e);
        }
    }

    private static List<PendingDelivery> readDueEvents(Connection connection, String dispatcher, String subscriber, int lane, int limit) {
        try (var stmt = connection.prepareStatement("""
            SELECT id, message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at, attempts,
                   next_attempt_at <= clock_timestamp() AS due
            FROM smd_dispatcher_queue
            WHERE dispatcher = ? AND subscriber = ? AND lane = ?
            ORDER BY id
            LIMIT ?
            """)) {
            stmt.setString(1, dispatcher);
            stmt.setString(2, subscriber);
            stmt.setInt(3, lane);
            stmt.setInt(4, limit);
            try (var rs = stmt.executeQuery()) {
                var deliveries = new ArrayList<PendingDelivery>();
                while (rs.next() && rs.getBoolean("due")) {
                    deliveries.add(mapPendingDelivery(rs));
                }
                return deliveries;
            }
        } catch (SQLException e) {
            throw new DispatcherStorageException("Failed to read lane " + lane + " of dispatcher '" + dispatcher + "' for subscriber '" + subscriber + "'", e);
        }
    }

    private static SerializedEvent mapSerializedEvent(ResultSet rs) throws SQLException {
        return new SerializedEvent(
            new MessageId(rs.getObject("message_id", UUID.class)),
            null,
            Optional.ofNullable(rs.getString("subject_id")),
            rs.getString("event_type"),
            rs.getBytes("serialized_payload"),
            rs.getBytes("serialized_metadata"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private static PendingDelivery mapPendingDelivery(ResultSet rs) throws SQLException {
        return new PendingDelivery(rs.getLong("id"), mapSerializedEvent(rs), rs.getInt("attempts"));
    }
}
