package app.dodb.smd.spring.event.processing;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.store.EventSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static java.util.Optional.ofNullable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

class EventStoreChannelProcessingIntegrationTest {

    @Test
    void batchSucceeded_updatesTokenWithProcessedSequence() {
        try (var context = createContext()) {
            var eventBus = context.getBean(EventBus.class);
            var handler = context.getBean(FailableTestEventHandler.class);

            eventBus.publish(new AnotherTestEvent());

            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).hasSize(1);
                assertThat(tokenLastProcessedSeq(context)).contains(1L);
                assertThat(tokenErrorCount(context)).contains(0);
            });
        }
    }

    @Test
    void gapDetected_marksGapThenSkipsAfterTimeout() {
        try (var context = createContext()) {
            var handler = context.getBean(FailableTestEventHandler.class);

            insertEventWithSequence(context, 1L);
            insertEventWithSequence(context, 3L);
            advanceSequence(context, 3L);

            // Gap detected: expected seq 2 but got seq 3
            await().untilAsserted(() ->
                assertThat(tokenGapSequenceNumber(context)).contains(2L)
            );

            // After gap timeout (2s): gap skipped, both events processed
            await().untilAsserted(() -> {
                assertThat(tokenLastProcessedSeq(context)).contains(3L);
                assertThat(tokenGapSequenceNumber(context)).contains(0L);
            });

            assertThat(handler.getHandledEvents()).hasSize(2);
        }
    }

    @Test
    void failed_marksFailedThenRetriesSuccessfully() {
        try (var context = createContext()) {
            var eventBus = context.getBean(EventBus.class);
            var handler = context.getBean(FailableTestEventHandler.class);

            handler.failNextNAttempts(1);
            eventBus.publish(new AnotherTestEvent());

            // First attempt fails
            await().untilAsserted(() ->
                assertThat(tokenErrorCount(context)).contains(1)
            );

            // Retry succeeds
            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).hasSize(1);
                assertThat(tokenErrorCount(context)).contains(0);
                assertThat(tokenLastProcessedSeq(context)).contains(1L);
            });
        }
    }

    @Test
    void abandoned_marksAbandonedAfterRetriesExhausted() {
        try (var context = createContext()) {
            var eventBus = context.getBean(EventBus.class);
            var handler = context.getBean(FailableTestEventHandler.class);

            handler.failNextNAttempts(Integer.MAX_VALUE);
            eventBus.publish(new AnotherTestEvent());

            // After max retries (2): abandoned (error_count=3, sequence advanced)
            await().untilAsserted(() -> {
                assertThat(tokenErrorCount(context)).contains(3);
                assertThat(tokenLastProcessedSeq(context)).contains(0L);
            });

            assertThat(handler.getHandledEvents()).isEmpty();
        }
    }

    private ConfigurableApplicationContext createContext() {
        return new SpringApplicationBuilder(EventStoreChannelProcessingTestConfiguration.class)
            .profiles("event-store-processing").web(NONE)
            .run();
    }

    private void insertEventWithSequence(ConfigurableApplicationContext context, long sequenceNumber) {
        var serializer = context.getBean(EventSerializer.class);
        var eventMessage = EventMessage.from(new AnotherTestEvent(), new Metadata(null, Instant.now(), null));
        var serialized = serializer.serialize(eventMessage);
        var ds = context.getBean(DataSource.class);

        try (var conn = ds.getConnection();
             var stmt = conn.prepareStatement("""
                     INSERT INTO smd_event_store (message_id, event_type, serialized_payload, serialized_metadata, created_at, sequence_number)
                     VALUES (?, ?, ?, ?, ?, ?)
                 """)) {
            stmt.setObject(1, serialized.messageId().value());
            stmt.setString(2, serialized.eventType());
            stmt.setBytes(3, serialized.serializedPayload());
            stmt.setBytes(4, serialized.serializedMetadata());
            stmt.setTimestamp(5, Timestamp.from(serialized.createdAt()));
            stmt.setLong(6, sequenceNumber);
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void advanceSequence(ConfigurableApplicationContext context, long value) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT setval('smd_event_store_sequence_number_seq', " + value + ", true)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Long> tokenLastProcessedSeq(ConfigurableApplicationContext context) {
        return queryToken(context, rs -> {
            long val = rs.getLong("last_processed_sequence_number");
            return ofNullable(val);
        });
    }

    private Optional<Integer> tokenErrorCount(ConfigurableApplicationContext context) {
        return queryToken(context, rs -> {
            var val = rs.getInt("error_count");
            return ofNullable(val);
        });
    }

    private Optional<Long> tokenGapSequenceNumber(ConfigurableApplicationContext context) {
        return queryToken(context, rs -> {
            long val = rs.getLong("gap_sequence_number");
            return ofNullable(val);
        });
    }

    private <T> T queryToken(ConfigurableApplicationContext context, ResultSetExtractor<T> extractor) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection(); var stmt = conn.prepareStatement("SELECT * FROM smd_token_store WHERE processing_group = ?")) {
            stmt.setString(1, ProcessingGroup.DEFAULT);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return extractor.extract(rs);
                }
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws Exception;
    }
}
