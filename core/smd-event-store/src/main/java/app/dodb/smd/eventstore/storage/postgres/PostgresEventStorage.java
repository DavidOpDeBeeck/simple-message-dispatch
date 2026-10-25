package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.Cursor;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.EventStorageException;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.eventstore.storage.StoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public class PostgresEventStorage implements EventStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresEventStorage.class);

    private final ConnectionProvider connectionProvider;

    public PostgresEventStorage(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public void append(NewEvent event) {
        connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO smd_event_store (event_id, aggregate_id, event_type, payload, metadata, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
                stmt.setObject(1, event.eventId().value());
                stmt.setString(2, event.aggregateId().orElse(null));
                stmt.setString(3, event.eventType());
                stmt.setBytes(4, event.payload());
                stmt.setBytes(5, event.metadata());
                stmt.setTimestamp(6, Timestamp.from(event.occurredAt()));

                int rowsInserted = stmt.executeUpdate();
                if (rowsInserted != 1) {
                    throw new EventStorageException("Failed to insert event: " + event.eventId());
                }

                LOGGER.debug("Appended event: eventId={}, eventType={}", event.eventId(), event.eventType());
            } catch (SQLException e) {
                throw new EventStorageException("Failed to append event: " + event.eventId(), e);
            }
        });
    }

    @Override
    public void append(NewEvent event, AggregateRevision revision) {
        connectionProvider.doWithConnection(connection -> {
            // A concurrent writer of the same aggregate version makes ON CONFLICT wait for its outcome:
            // a commit leaves this insert without a row, a rollback lets it proceed.
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO smd_event_store
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload, metadata, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (aggregate_type, aggregate_id, aggregate_version) WHERE aggregate_type IS NOT NULL DO NOTHING
                """)) {
                stmt.setObject(1, event.eventId().value());
                stmt.setString(2, revision.aggregateType());
                stmt.setString(3, revision.aggregateId());
                stmt.setLong(4, revision.version());
                stmt.setString(5, event.eventType());
                stmt.setBytes(6, event.payload());
                stmt.setBytes(7, event.metadata());
                stmt.setTimestamp(8, Timestamp.from(event.occurredAt()));

                if (stmt.executeUpdate() != 1) {
                    throw new ConcurrentAggregateChangeException(revision.aggregateType(), revision.aggregateId(), revision.expectedVersion());
                }

                LOGGER.debug("Appended aggregate event: eventId={}, aggregateType={}, aggregateId={}, version={}",
                    event.eventId(), revision.aggregateType(), revision.aggregateId(), revision.version());
            } catch (SQLException e) {
                throw new EventStorageException("Failed to append aggregate event: eventId=%s, aggregateType=%s, aggregateId=%s, version=%d"
                    .formatted(event.eventId(), revision.aggregateType(), revision.aggregateId(), revision.version()), e);
            }
        });
    }

    @Override
    public Cursor<StoredEvent> load(long afterGlobalPosition, int limit) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT global_position, event_id, aggregate_id, event_type, payload, metadata, occurred_at
                FROM smd_event_store
                WHERE global_position > ?
                ORDER BY global_position
                LIMIT ?
                """)) {
                statement.setLong(1, afterGlobalPosition);
                statement.setInt(2, limit);

                LOGGER.debug("Loading events after globalPosition={} (limit={})", afterGlobalPosition, limit);
                var resultSet = statement.executeQuery();

                var events = new ArrayDeque<StoredEvent>();
                while (resultSet.next()) {
                    events.add(mapToStoredEvent(resultSet));
                }
                return cursor(events);
            } catch (SQLException e) {
                throw new EventStorageException("Failed to load events: afterGlobalPosition=" + afterGlobalPosition, e);
            }
        });
    }

    @Override
    public List<StoredEvent> loadAggregateStream(String aggregateType, String aggregateId) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT global_position, event_id, aggregate_id, event_type, payload, metadata, occurred_at
                FROM smd_event_store
                WHERE aggregate_type = ? AND aggregate_id = ?
                ORDER BY aggregate_version
                """)) {
                statement.setString(1, aggregateType);
                statement.setString(2, aggregateId);

                var resultSet = statement.executeQuery();
                var events = new ArrayList<StoredEvent>();
                while (resultSet.next()) {
                    events.add(mapToStoredEvent(resultSet));
                }
                return List.copyOf(events);
            } catch (SQLException e) {
                throw new EventStorageException("Failed to load aggregate stream: aggregateType=%s, aggregateId=%s".formatted(aggregateType, aggregateId), e);
            }
        });
    }

    private static StoredEvent mapToStoredEvent(ResultSet resultSet) throws SQLException {
        return new StoredEvent(
            resultSet.getLong("global_position"),
            new MessageId((UUID) resultSet.getObject("event_id")),
            Optional.ofNullable(resultSet.getString("aggregate_id")),
            resultSet.getString("event_type"),
            resultSet.getBytes("payload"),
            resultSet.getBytes("metadata"),
            resultSet.getTimestamp("occurred_at").toInstant()
        );
    }

    private static Cursor<StoredEvent> cursor(Deque<StoredEvent> events) {
        return new Cursor<>() {

            @Override
            public boolean hasNext() {
                return !events.isEmpty();
            }

            @Override
            public StoredEvent next() {
                if (hasNext()) {
                    return events.remove();
                }
                throw new NoSuchElementException("No more events in cursor");
            }

            @Override
            public void close() {
            }
        };
    }
}
