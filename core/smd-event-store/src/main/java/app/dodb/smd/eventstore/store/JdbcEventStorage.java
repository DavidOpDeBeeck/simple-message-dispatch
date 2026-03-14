package app.dodb.smd.eventstore.store;

import app.dodb.smd.eventstore.framework.ConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

import static java.util.Objects.requireNonNull;

public class JdbcEventStorage implements EventStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcEventStorage.class);

    private static final String INSERT_EVENT = """
        INSERT INTO smd_event_store (message_id, event_type, serialized_payload, serialized_metadata, created_at)
        VALUES (?, ?, ?, ?, ?)
        """;

    private final ConnectionProvider connectionProvider;

    public JdbcEventStorage(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public void store(SerializedEvent event) {
        connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(INSERT_EVENT)) {
                stmt.setObject(1, event.messageId().value());
                stmt.setString(2, event.eventType());
                stmt.setBytes(3, event.serializedPayload());
                stmt.setBytes(4, event.serializedMetadata());
                stmt.setTimestamp(5, Timestamp.from(event.createdAt()));

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
        return JdbcEventCursor.openFrom(connectionProvider, lastProcessedSequenceNumber, limit);
    }

    public static class EventStorageException extends RuntimeException {

        public EventStorageException(String message) {
            super(message);
        }

        public EventStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
