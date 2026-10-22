package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.eventstore.storage.postgres.PostgresEventStorage;
import app.dodb.smd.eventstore.storage.postgres.PostgresEventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.postgres.PostgresTokenStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static app.dodb.smd.eventstore.storage.EventLog.EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

class EventStoreLogsMigrationIntegrationTest {

    private static final String RELEASED_SCHEMA_URL = "jdbc:tc:postgresql:18-alpine:///migration?TC_INITSCRIPT=event-store-schema-0.0.12.sql";

    @Test
    void migration_releasedSchemaWithState_keepsStateInEventsLog() throws Exception {
        try (var connection = DriverManager.getConnection(RELEASED_SCHEMA_URL, "test_user", "test_password")) {
            // Given
            var messageId = UUID.randomUUID();
            execute(connection, """
                INSERT INTO smd_event_store (message_id, subject_id, event_type, serialized_payload, serialized_metadata, created_at)
                VALUES ('%s', 'ticket-1', 'ticket.opened.v1', '\\x7b7d', '\\x7b7d', now())
                """.formatted(messageId));
            execute(connection, "INSERT INTO smd_token_store (processing_group, last_processed_sequence_number) VALUES ('projection', 1)");
            execute(connection, """
                INSERT INTO smd_event_sequence_state (processing_group, subject_id, status, last_processed_sequence_number, error_count)
                VALUES ('projection', 'ticket-1', 'ACTIVE', 1, 0)
                """);

            // When
            runScript(connection, "/db/smd/event-store-logs-migration.sql");

            // Then
            var connectionProvider = singleConnection(connection);
            var eventStorage = new PostgresEventStorage(connectionProvider);
            try (var events = eventStorage.load(EVENTS, 0L, 10)) {
                assertThat(events.next().messageId().value()).isEqualTo(messageId);
                assertThat(events.hasNext()).isFalse();
            }
            assertThat(new PostgresTokenStore(connectionProvider).tokenState("projection").flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
            assertThat(new PostgresEventSubjectSequenceStore(connectionProvider).eventSequenceState("projection", "ticket-1")
                .flatMap(EventSequenceState::lastProcessedSequenceNumber)).contains(1L);
            assertThat(eventStorage.storeIfAbsent(EventLog.outbox("tickets"), new SerializedEvent(
                new MessageId(messageId), null, Optional.of("ticket-1"), "ticket.opened.v1", new byte[0], new byte[0], Instant.now()
            ))).isTrue();
        }
    }

    private static void runScript(Connection connection, String resource) throws SQLException {
        String script;
        try (var stream = EventStoreLogsMigrationIntegrationTest.class.getResourceAsStream(resource)) {
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var statements = Arrays.stream(script.split(";"))
            .map(statement -> statement.lines().filter(line -> !line.startsWith("--")).reduce("", (left, right) -> left + "\n" + right).trim())
            .filter(statement -> !statement.isEmpty())
            .toList();
        for (var statement : statements) {
            execute(connection, statement);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static ConnectionProvider singleConnection(Connection connection) {
        return new ConnectionProvider() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public void releaseConnection(Connection released) {
            }
        };
    }
}
