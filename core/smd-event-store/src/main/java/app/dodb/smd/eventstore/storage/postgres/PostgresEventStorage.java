package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.Cursor;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.EventStorageException;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.Deque;
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
    public void store(EventLog log, SerializedEvent event) {
        connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO smd_event_store (log, message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
                setEventParameters(stmt, log, event);

                int rowsInserted = stmt.executeUpdate();
                if (rowsInserted != 1) {
                    throw new EventStorageException("Failed to insert event: log=" + log.name() + ", messageId=" + event.messageId());
                }

                LOGGER.debug("Stored event: log={}, messageId={}, eventType={}", log.name(), event.messageId(), event.eventType());
            } catch (SQLException e) {
                throw new EventStorageException("Failed to store event: log=" + log.name() + ", messageId=" + event.messageId(), e);
            }
        });
    }

    @Override
    public boolean storeIfAbsent(EventLog log, SerializedEvent event) {
        // INSERT ... ON CONFLICT DO NOTHING would draw a sequence number for every duplicate and leave a gap that stalls every cursor.
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO smd_event_store (log, message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM smd_event_store WHERE log = ? AND message_id = ?)
                """)) {
                setEventParameters(stmt, log, event);
                stmt.setString(8, log.name());
                stmt.setObject(9, event.messageId().value());

                var stored = stmt.executeUpdate() == 1;
                LOGGER.debug("Stored event if absent: log={}, messageId={}, stored={}", log.name(), event.messageId(), stored);
                return stored;
            } catch (SQLException e) {
                throw new EventStorageException("Failed to store event if absent: log=" + log.name() + ", messageId=" + event.messageId(), e);
            }
        });
    }

    @Override
    public Cursor<SerializedEvent> load(EventLog log, long lastProcessedSequenceNumber, int limit) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at, sequence_number
                FROM smd_event_store
                WHERE log = ? AND sequence_number > ?
                ORDER BY sequence_number
                LIMIT ?
                """)) {
                statement.setString(1, log.name());
                statement.setLong(2, lastProcessedSequenceNumber);
                statement.setInt(3, limit);

                LOGGER.debug("Loading events: log={}, afterSequenceNumber={}, limit={}", log.name(), lastProcessedSequenceNumber, limit);
                var resultSet = statement.executeQuery();

                var events = new ArrayDeque<SerializedEvent>();
                while (resultSet.next()) {
                    events.add(mapToSerializedEvent(resultSet));
                }
                return cursor(events);
            } catch (SQLException e) {
                throw new EventStorageException("Failed to load events: log=" + log.name() + ", lastProcessedSequenceNumber=" + lastProcessedSequenceNumber, e);
            }
        });
    }

    @Override
    public long countStoredInOtherLogs(EventLog log, long afterSequenceNumber, long beforeSequenceNumber) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM smd_event_store
                WHERE sequence_number > ? AND sequence_number < ? AND log <> ?
                """)) {
                statement.setLong(1, afterSequenceNumber);
                statement.setLong(2, beforeSequenceNumber);
                statement.setString(3, log.name());

                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            } catch (SQLException e) {
                throw new EventStorageException("Failed to count events stored in other logs: log=" + log.name()
                    + ", afterSequenceNumber=" + afterSequenceNumber + ", beforeSequenceNumber=" + beforeSequenceNumber, e);
            }
        });
    }

    private static void setEventParameters(PreparedStatement stmt, EventLog log, SerializedEvent event) throws SQLException {
        stmt.setString(1, log.name());
        stmt.setObject(2, event.messageId().value());
        stmt.setString(3, event.subjectId().orElse(null));
        stmt.setString(4, event.eventType());
        stmt.setBytes(5, event.serializedPayload());
        stmt.setBytes(6, event.serializedMetadata());
        stmt.setTimestamp(7, Timestamp.from(event.createdAt()));
    }

    private static SerializedEvent mapToSerializedEvent(ResultSet resultSet) throws SQLException {
        return new SerializedEvent(
            new MessageId((UUID) resultSet.getObject("message_id")),
            resultSet.getLong("sequence_number"),
            Optional.ofNullable(resultSet.getString("subject_id")),
            resultSet.getString("event_type"),
            resultSet.getBytes("serialized_payload"),
            resultSet.getBytes("serialized_metadata"),
            resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static Cursor<SerializedEvent> cursor(Deque<SerializedEvent> events) {
        return new Cursor<>() {

            @Override
            public boolean hasNext() {
                return !events.isEmpty();
            }

            @Override
            public SerializedEvent next() {
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
