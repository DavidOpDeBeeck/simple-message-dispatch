package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
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
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalLong;
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
            // A concurrent writer of the same subject version makes ON CONFLICT wait for its outcome:
            // a commit leaves this insert without a row, a rollback lets it proceed.
            // Events without a subject version never match the partial index, so they always insert.
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO smd_event_store
                    (event_id, subject_type, subject_id, subject_version, event_type, payload, metadata, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subject_type, subject_id, subject_version) WHERE subject_version IS NOT NULL DO NOTHING
                """)) {
                stmt.setObject(1, event.eventId().value());
                stmt.setString(2, event.subject().map(Subject::type).orElse(null));
                stmt.setString(3, event.subject().map(Subject::id).orElse(null));
                if (event.subjectVersion().isPresent()) {
                    stmt.setLong(4, event.subjectVersion().get());
                } else {
                    stmt.setNull(4, Types.BIGINT);
                }
                stmt.setString(5, event.eventType());
                stmt.setBytes(6, event.payload());
                stmt.setBytes(7, event.metadata());
                stmt.setTimestamp(8, Timestamp.from(event.occurredAt()));

                if (stmt.executeUpdate() != 1) {
                    throw event.subjectVersion().isPresent()
                        ? new ConcurrentAggregateChangeException(event.subject().orElseThrow(), event.subjectVersion().get() - 1L)
                        : new EventStorageException("Event was not appended: eventId=%s, subject=%s".formatted(event.eventId(), event.subject()));
                }

                LOGGER.debug("Appended event: eventId={}, eventType={}, subject={}, subjectVersion={}",
                    event.eventId(), event.eventType(), event.subject(), event.subjectVersion());
            } catch (SQLException e) {
                throw new EventStorageException("Failed to append event: eventId=%s, subject=%s".formatted(event.eventId(), event.subject()), e);
            }
        });
    }

    @Override
    public Cursor<StoredEvent> load(long afterGlobalPosition, int limit) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT global_position, event_id, subject_type, subject_id, subject_version, event_type, payload, metadata, occurred_at
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
    public List<StoredEvent> loadSubjectStream(Subject subject) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT global_position, event_id, subject_type, subject_id, subject_version, event_type, payload, metadata, occurred_at
                FROM smd_event_store
                WHERE subject_type = ? AND subject_id = ? AND subject_version IS NOT NULL
                ORDER BY subject_version
                """)) {
                statement.setString(1, subject.type());
                statement.setString(2, subject.id());

                var resultSet = statement.executeQuery();
                var events = new ArrayList<StoredEvent>();
                while (resultSet.next()) {
                    events.add(mapToStoredEvent(resultSet));
                }
                return List.copyOf(events);
            } catch (SQLException e) {
                throw new EventStorageException("Failed to load subject stream: subject=" + subject, e);
            }
        });
    }

    private static StoredEvent mapToStoredEvent(ResultSet resultSet) throws SQLException {
        return new StoredEvent(
            resultSet.getLong("global_position"),
            new MessageId((UUID) resultSet.getObject("event_id")),
            subject(resultSet),
            optionalLong(resultSet, "subject_version"),
            resultSet.getString("event_type"),
            resultSet.getBytes("payload"),
            resultSet.getBytes("metadata"),
            resultSet.getTimestamp("occurred_at").toInstant()
        );
    }

    private static Optional<Subject> subject(ResultSet resultSet) throws SQLException {
        var subjectId = resultSet.getString("subject_id");
        return subjectId == null ? Optional.empty() : Optional.of(new Subject(resultSet.getString("subject_type"), subjectId));
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
