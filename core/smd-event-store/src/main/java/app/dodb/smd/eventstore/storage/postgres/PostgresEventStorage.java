package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.Cursor;
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
    public void store(SerializedEvent event) {
        connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO smd_event_store (message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
                stmt.setObject(1, event.messageId().value());
                stmt.setString(2, event.subjectId().orElse(null));
                stmt.setString(3, event.eventType());
                stmt.setBytes(4, event.serializedPayload());
                stmt.setBytes(5, event.serializedMetadata());
                stmt.setTimestamp(6, Timestamp.from(event.createdAt()));

                int rowsInserted = stmt.executeUpdate();
                if (rowsInserted != 1) {
                    throw new EventStorageException("Failed to insert event: " + event.messageId());
                }

                LOGGER.debug("Stored event: messageId={}, eventType={}", event.messageId(), event.eventType());
            } catch (SQLException e) {
                throw new EventStorageException("Failed to store event: " + event.messageId(), e);
            }
        });
    }

    @Override
    public Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at, sequence_number
                FROM smd_event_store
                WHERE sequence_number > ?
                ORDER BY sequence_number
                LIMIT ?
                """)) {
                statement.setLong(1, lastProcessedSequenceNumber);
                statement.setInt(2, limit);

                LOGGER.debug("Loading events after sequenceNumber={} (limit={})", lastProcessedSequenceNumber, limit);
                var resultSet = statement.executeQuery();

                var events = new ArrayDeque<SerializedEvent>();
                while (resultSet.next()) {
                    events.add(mapToSerializedEvent(resultSet));
                }
                return cursor(events);
            } catch (SQLException e) {
                throw new EventStorageException("Failed to load events: lastProcessedSequenceNumber=" + lastProcessedSequenceNumber, e);
            }
        });
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
