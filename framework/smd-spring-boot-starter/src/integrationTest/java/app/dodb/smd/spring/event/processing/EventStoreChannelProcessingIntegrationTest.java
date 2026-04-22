package app.dodb.smd.spring.event.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.TokenStore;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.time.Duration.ofSeconds;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

class EventStoreChannelProcessingIntegrationTest {

    private static final String SCHEDULING_DISABLED = "smd.event-store.scheduling.enabled=false";
    private static final String PROCESSING_ID = "processingId";
    private static final String SEQUENCE_NUMBER = "sequenceNumber";
    private static final int MANUAL_BATCH_SIZE = 10;
    private static final Duration MANUAL_POLLING_DELAY = ofMillis(50);

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
                assertThat(storedEventTypes(context)).containsExactly(AnotherTestEvent.class.getName());
            });
        }
    }

    @Test
    void batchSucceeded_processesMultipleEventsInSinglePoll() {
        try (var context = createContextWithoutScheduling()) {
            var processingGroup = "manual-batch-processing-group";
            var processingIds = new CopyOnWriteArraySet<String>();
            var processedSequences = new CopyOnWriteArrayList<String>();

            insertEventsAndAdvanceSequence(context, 1L, 2L, 3L);

            try (var channel = createManualChannel(context)) {
                channel.subscribe(new EventChannelListener() {
                    @Override
                    public String processingGroup() {
                        return processingGroup;
                    }

                    @Override
                    public <E extends Event> void on(EventMessage<E> eventMessage) {
                        var metadata = eventMessage.metadata().properties();
                        processingIds.add(metadata.get(PROCESSING_ID));
                        processedSequences.add(metadata.get(SEQUENCE_NUMBER));
                    }
                });

                await().untilAsserted(() -> {
                    assertThat(processingIds).hasSize(1).doesNotContainNull();
                    assertThat(processedSequences).containsExactly("1", "2", "3");
                    assertThat(tokenLastProcessedSeq(context, processingGroup)).contains(3L);
                    assertThat(tokenErrorCount(context, processingGroup)).contains(0);
                });
            }
        }
    }

    @Test
    void gapDetected_marksGapThenSkipsAfterTimeout() {
        try (var context = createContext()) {
            var handler = context.getBean(FailableTestEventHandler.class);

            insertEventsAndAdvanceSequence(context, 1L, 3L);

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
    void failedHandler_rollsBackDatabaseSideEffects() {
        try (var context = createContext()) {
            var eventBus = context.getBean(EventBus.class);
            createSideEffectsTable(context);

            eventBus.publish(new SideEffectTestEvent());

            await().untilAsserted(() -> {
                assertThat(tokenErrorCount(context))
                    .hasValueSatisfying(errorCount -> assertThat(errorCount).isGreaterThanOrEqualTo(1));
                assertThat(sideEffectCount(context)).isZero();
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

            // After max retries (2): abandoned with error_count=3 and sequence not advanced.
            await().untilAsserted(() -> {
                assertThat(tokenErrorCount(context)).contains(3);
                assertThat(tokenLastProcessedSeq(context)).contains(0L);
            });

            assertThat(handler.getHandledEvents()).isEmpty();
        }
    }

    @Test
    void claimToken_whenAlreadyClaimed_returnsEmptyWithoutBlockingThenCanClaimAfterCommit() throws Exception {
        try (var context = createContextWithoutScheduling();
             var executor = Executors.newFixedThreadPool(2)) {
            var tokenStore = context.getBean(TokenStore.class);
            var transactionProvider = context.getBean(TransactionProvider.class);

            var processingGroup = "contended-processing-group";
            var firstClaimed = new CountDownLatch(1);
            var releaseFirstClaim = new CountDownLatch(1);

            var firstClaim = executor.submit(() -> transactionProvider.doInNewTransaction(() -> {
                assertThat(tokenStore.claimToken(processingGroup)).isPresent();
                firstClaimed.countDown();
                awaitLatch(releaseFirstClaim);
                return null;
            }));

            awaitLatch(firstClaimed);

            var startedAt = System.nanoTime();
            var secondClaim = transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup));
            var elapsed = ofNanos(System.nanoTime() - startedAt);

            assertThat(secondClaim).isEmpty();
            assertThat(elapsed).isLessThan(ofSeconds(1));

            releaseFirstClaim.countDown();
            firstClaim.get(5, SECONDS);

            assertThat(transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup))).isPresent();
        }
    }

    @Test
    void markProcessed_doesNotRegressTokenAndClearsErrorStateWhenAdvanced() {
        try (var context = createContextWithoutScheduling()) {
            var tokenStore = context.getBean(TokenStore.class);
            var transactionProvider = context.getBean(TransactionProvider.class);
            var processingGroup = "monotonic-processing-group";

            insertTokenState(context, processingGroup, 5L, 2, 6L);

            transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup)
                .ifPresent(token -> token.markProcessed(4L)));

            assertThat(tokenLastProcessedSeq(context, processingGroup)).contains(5L);
            assertThat(tokenErrorCount(context, processingGroup)).contains(2);
            assertThat(tokenGapSequenceNumber(context, processingGroup)).contains(6L);

            transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup)
                .ifPresent(token -> token.markProcessed(6L)));

            assertThat(tokenLastProcessedSeq(context, processingGroup)).contains(6L);
            assertThat(tokenErrorCount(context, processingGroup)).contains(0);
            assertThat(tokenGapSequenceNumber(context, processingGroup)).contains(0L);
        }
    }

    @Test
    void sameProcessingGroup_whenTwoChannelsPollConcurrently_processesEventOnce() {
        try (var context = createContextWithoutScheduling()) {
            var processingGroup = "manual-concurrent-processing-group";
            var handledCount = new AtomicInteger();
            insertEventsAndAdvanceSequence(context, 1L);

            try (var firstChannel = createManualChannel(context);
                 var secondChannel = createManualChannel(context)) {
                var listener = new EventChannelListener() {
                    @Override
                    public String processingGroup() {
                        return processingGroup;
                    }

                    @Override
                    public <E extends Event> void on(EventMessage<E> eventMessage) {
                        handledCount.incrementAndGet();
                        sleep(ofMillis(300));
                    }
                };

                firstChannel.subscribe(listener);
                secondChannel.subscribe(listener);

                await().untilAsserted(() -> {
                    assertThat(tokenLastProcessedSeq(context, processingGroup)).contains(1L);
                    assertThat(handledCount).hasValue(1);
                });

                await().during(ofMillis(500)).untilAsserted(() ->
                    assertThat(handledCount).hasValue(1)
                );
            }
        }
    }

    private ConfigurableApplicationContext createContext() {
        return createContext(new String[0]);
    }

    private ConfigurableApplicationContext createContextWithoutScheduling() {
        return createContext(SCHEDULING_DISABLED);
    }

    private ConfigurableApplicationContext createContext(String... properties) {
        return new SpringApplicationBuilder(EventStoreChannelProcessingTestConfiguration.class)
            .profiles("event-store-processing")
            .properties(properties)
            .web(NONE)
            .run();
    }

    private EventStoreChannel createManualChannel(ConfigurableApplicationContext context) {
        return new EventStoreChannel(EventStoreChannelConfig.withoutDefaults()
            .transactionProvider(context.getBean(TransactionProvider.class))
            .interceptors(List.of())
            .eventStorage(context.getBean(EventStorage.class))
            .eventSerializer(context.getBean(EventSerializer.class))
            .tokenStore(context.getBean(TokenStore.class))
            .schedulingConfig(EventStoreChannelConfig.SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(Executors.newSingleThreadScheduledExecutor())
                .initialDelay(ZERO)
                .pollingDelay(MANUAL_POLLING_DELAY)
                .build())
            .processingConfig(EventStoreChannelConfig.ProcessingConfig.withoutDefaults()
                .maxRetries(1)
                .batchSize(MANUAL_BATCH_SIZE)
                .retryBackoffStrategy(fixed(ZERO))
                .gapTimeout(ofSeconds(1))
                .build())
            .build());
    }

    private void insertEventsAndAdvanceSequence(ConfigurableApplicationContext context, long... sequenceNumbers) {
        long highestSequenceNumber = 0L;
        for (var sequenceNumber : sequenceNumbers) {
            insertEventWithSequence(context, sequenceNumber);
            highestSequenceNumber = Math.max(highestSequenceNumber, sequenceNumber);
        }
        advanceSequence(context, highestSequenceNumber);
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void createSideEffectsTable(ConfigurableApplicationContext context) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS event_handler_side_effects
                (
                    id BIGSERIAL PRIMARY KEY,
                    description VARCHAR(255) NOT NULL
                )
                """);
            stmt.execute("DELETE FROM event_handler_side_effects");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int sideEffectCount(ConfigurableApplicationContext context) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM event_handler_side_effects")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    private void insertTokenState(ConfigurableApplicationContext context,
                                  String processingGroup,
                                  long lastProcessedSequenceNumber,
                                  int errorCount,
                                  long gapSequenceNumber) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT INTO smd_token_store
                     (processing_group, last_processed_sequence_number, last_updated_at, error_count, last_gap_detected_at, gap_sequence_number)
                 VALUES (?, ?, ?, ?, ?, ?)
                 """)) {
            var now = Timestamp.from(Instant.now());
            stmt.setString(1, processingGroup);
            stmt.setLong(2, lastProcessedSequenceNumber);
            stmt.setTimestamp(3, now);
            stmt.setInt(4, errorCount);
            stmt.setTimestamp(5, now);
            stmt.setLong(6, gapSequenceNumber);
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Long> tokenLastProcessedSeq(ConfigurableApplicationContext context) {
        return tokenLastProcessedSeq(context, ProcessingGroup.DEFAULT);
    }

    private Optional<Long> tokenLastProcessedSeq(ConfigurableApplicationContext context, String processingGroup) {
        return queryToken(context, rs -> {
            long val = rs.getLong("last_processed_sequence_number");
            return ofNullable(val);
        }, processingGroup);
    }

    private Optional<Integer> tokenErrorCount(ConfigurableApplicationContext context) {
        return tokenErrorCount(context, ProcessingGroup.DEFAULT);
    }

    private Optional<Integer> tokenErrorCount(ConfigurableApplicationContext context, String processingGroup) {
        return queryToken(context, rs -> {
            var val = rs.getInt("error_count");
            return ofNullable(val);
        }, processingGroup);
    }

    private Optional<Long> tokenGapSequenceNumber(ConfigurableApplicationContext context) {
        return tokenGapSequenceNumber(context, ProcessingGroup.DEFAULT);
    }

    private Optional<Long> tokenGapSequenceNumber(ConfigurableApplicationContext context, String processingGroup) {
        return queryToken(context, rs -> {
            long val = rs.getLong("gap_sequence_number");
            return ofNullable(val);
        }, processingGroup);
    }

    private List<String> storedEventTypes(ConfigurableApplicationContext context) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection();
             var stmt = conn.prepareStatement("SELECT event_type FROM smd_event_store ORDER BY sequence_number");
             var rs = stmt.executeQuery()) {
            var eventTypes = new ArrayList<String>();
            while (rs.next()) {
                eventTypes.add(rs.getString("event_type"));
            }
            return eventTypes;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T queryToken(ConfigurableApplicationContext context, ResultSetExtractor<T> extractor, String processingGroup) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection(); var stmt = conn.prepareStatement("SELECT * FROM smd_token_store WHERE processing_group = ?")) {
            stmt.setString(1, processingGroup);
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

    interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws Exception;
    }
}
