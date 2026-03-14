package app.dodb.smd.eventstore.store;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.framework.ConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

class JdbcEventCursor implements Cursor<SerializedEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcEventCursor.class);

    private static final String SELECT_EVENTS_AFTER_SEQUENCE = """
        SELECT message_id, event_type, serialized_payload, serialized_metadata, created_at, sequence_number
        FROM smd_event_store
        WHERE sequence_number > ?
        ORDER BY sequence_number
        LIMIT ?
        """;

    private final Deque<SerializedEvent> events;

    private JdbcEventCursor(Deque<SerializedEvent> events) {
        this.events = requireNonNull(events);
    }

    static JdbcEventCursor openFrom(ConnectionProvider connectionProvider, long lastProcessedSequenceNumber, int limit) {
        return connectionProvider.doWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_EVENTS_AFTER_SEQUENCE)) {
                statement.setLong(1, lastProcessedSequenceNumber);
                statement.setInt(2, limit);

                LOGGER.debug("Loading events after sequenceNumber={} (limit={})", lastProcessedSequenceNumber, limit);
                var resultSet = statement.executeQuery();

                var events = new ArrayDeque<SerializedEvent>();
                while (resultSet.next()) {
                    events.add(mapToSerializedEvent(resultSet));
                }
                return new JdbcEventCursor(events);
            } catch (SQLException e) {
                throw new CursorException("Failed to load events", e);
            }
        });
    }

    @Override
    public boolean hasNext() {
        return !events.isEmpty();
    }

    @Override
    public SerializedEvent next() {
        if (hasNext()) {
            return events.poll();
        }
        throw new NoSuchElementException("No more events in cursor");
    }

    @Override
    public void close() {
        // no resources to release — events are preloaded and connection is already returned
    }

    private static SerializedEvent mapToSerializedEvent(ResultSet resultSet) throws SQLException {
        return new SerializedEvent(
            new MessageId((UUID) resultSet.getObject("message_id")),
            resultSet.getLong("sequence_number"),
            resultSet.getString("event_type"),
            resultSet.getBytes("serialized_payload"),
            resultSet.getBytes("serialized_metadata"),
            resultSet.getTimestamp("created_at").toInstant()
        );
    }

    static class CursorException extends RuntimeException {
        CursorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
