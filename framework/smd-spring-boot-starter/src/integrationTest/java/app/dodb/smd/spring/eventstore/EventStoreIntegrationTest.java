package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.TokenStore;
import app.dodb.smd.eventstore.store.SerializedEvent;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import app.dodb.smd.eventstore.store.serialization.EventTypeResolutionException;
import app.dodb.smd.eventstore.store.serialization.EventTypeResolver;
import app.dodb.smd.eventstore.store.serialization.JacksonEventSerializer;
import app.dodb.smd.spring.event.processing.AnotherTestEvent;
import app.dodb.smd.spring.event.processing.EventStoreChannelProcessingTestConfiguration;
import app.dodb.smd.spring.event.processing.FailableTestEventHandler;
import app.dodb.smd.spring.event.processing.SideEffectTestEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;
import static tools.jackson.databind.SerializationFeature.INDENT_OUTPUT;

class EventStoreIntegrationTest {

    private static final String SCHEDULING_DISABLED = "smd.event-store.scheduling.enabled=false";
    private static final String PROCESSING_ID = "processingId";
    private static final String SEQUENCE_NUMBER = "sequenceNumber";
    private static final int MANUAL_BATCH_SIZE = 10;
    private static final Duration MANUAL_POLLING_DELAY = ofMillis(50);
    private static final Instant TIMESTAMP = Instant.parse("2026-04-22T10:15:30Z");

    @Nested
    class Processing {

        @Test
        void publishedEvent_isStoredHandledAndMarksTokenProcessed() {
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
        void manualPoll_processesMultipleEventsInSingleBatch() {
            try (var context = createContext(SCHEDULING_DISABLED)) {
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

                await().untilAsserted(() ->
                    assertThat(tokenGapSequenceNumber(context)).contains(2L)
                );

                await().untilAsserted(() -> {
                    assertThat(tokenLastProcessedSeq(context)).contains(3L);
                    assertThat(tokenGapSequenceNumber(context)).contains(0L);
                });

                assertThat(handler.getHandledEvents()).hasSize(2);
            }
        }

        @Test
        void handlerFailure_recordsErrorThenRetriesSuccessfully() {
            try (var context = createContext()) {
                var eventBus = context.getBean(EventBus.class);
                var handler = context.getBean(FailableTestEventHandler.class);

                handler.failNextNAttempts(1);
                eventBus.publish(new AnotherTestEvent());

                await().untilAsserted(() ->
                    assertThat(tokenErrorCount(context)).contains(1)
                );

                await().untilAsserted(() -> {
                    assertThat(handler.getHandledEvents()).hasSize(1);
                    assertThat(tokenErrorCount(context)).contains(0);
                    assertThat(tokenLastProcessedSeq(context)).contains(1L);
                });
            }
        }

        @Test
        void handlerFailure_rollsBackDatabaseSideEffects() {
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
        void handlerFailure_marksEventAbandonedAfterRetriesExhausted() {
            try (var context = createContext()) {
                var eventBus = context.getBean(EventBus.class);
                var handler = context.getBean(FailableTestEventHandler.class);

                handler.failNextNAttempts(Integer.MAX_VALUE);
                eventBus.publish(new AnotherTestEvent());

                await().untilAsserted(() -> {
                    assertThat(tokenErrorCount(context)).contains(3);
                    assertThat(tokenLastProcessedSeq(context)).contains(0L);
                });

                assertThat(handler.getHandledEvents()).isEmpty();
            }
        }

        @Test
        void claimToken_whenAlreadyClaimed_returnsEmptyWithoutBlockingThenCanClaimAfterCommit() throws Exception {
            try (var context = createContext(SCHEDULING_DISABLED);
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
            try (var context = createContext(SCHEDULING_DISABLED)) {
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
            try (var context = createContext(SCHEDULING_DISABLED)) {
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
    }

    @Nested
    class SerializerAutoConfiguration {

        @Test
        void createsJacksonEventSerializerWhenNoCustomEventSerializerBean() {
            try (var context = createContext(SCHEDULING_DISABLED)) {
                var serializer = context.getBean(EventSerializer.class);

                assertThat(serializer).isInstanceOf(JacksonEventSerializer.class);
            }
        }

        @Test
        void usesCustomEventSerializerBean() {
            try (var context = createContext(CustomEventSerializerConfiguration.class)) {
                var serializer = context.getBean(EventSerializer.class);

                assertThat(serializer).isInstanceOf(CustomEventSerializer.class);
                assertThat(context.getBeansOfType(EventSerializer.class)).hasSize(1);
                assertThat(context.getBean(EventStoreChannelConfig.class).getEventSerializer()).isSameAs(serializer);
            }
        }

        @Test
        void usesCustomEventTypeResolverBean() {
            try (var context = createContext(CustomEventTypeResolverConfiguration.class)) {
                var serializer = context.getBean(EventSerializer.class);
                var eventMessage = EventMessage.from(new SerializerTestEvent("value"), new Metadata(null, TIMESTAMP, null));

                var serialized = serializer.serialize(eventMessage);
                var deserialized = serializer.deserialize(serialized);

                assertThat(serialized.eventType()).isEqualTo(CustomEventTypeResolver.EVENT_TYPE);
                assertThat(deserialized.payload()).isEqualTo(new SerializerTestEvent("value"));
            }
        }

        @Test
        void registersSmdJacksonModule() {
            try (var context = createContext(SCHEDULING_DISABLED)) {
                var serializer = context.getBean(EventSerializer.class);
                var principal = SimplePrincipal.create();
                var eventMessage = EventMessage.from(new SerializerTestEvent("value"), new Metadata(principal, TIMESTAMP, null));

                var serialized = serializer.serialize(eventMessage);
                var deserialized = serializer.deserialize(serialized);

                assertThat(new String(serialized.serializedMetadata(), UTF_8))
                    .contains("\"type\":\"" + SimplePrincipal.class.getName() + "\"");
                assertThat(deserialized.metadata().principal()).isEqualTo(principal);
            }
        }

        @Test
        void appliesJacksonModuleBeans() {
            try (var context = createContext(EventSerializerJacksonModuleConfiguration.class)) {
                var serializer = context.getBean(EventSerializer.class);
                var eventMessage = EventMessage.from(new SerializerTestEvent("value"), new Metadata(null, TIMESTAMP, null));

                var serialized = serializer.serialize(eventMessage);

                assertThat(new String(serialized.serializedPayload(), UTF_8))
                    .contains("\"customValue\":\"value\"")
                    .doesNotContain("\"value\":\"value\"");
            }
        }

        @Test
        void appliesJsonMapperBuilderCustomizerBeans() {
            try (var context = createContext(EventSerializerJsonMapperBuilderCustomizerConfiguration.class)) {
                var serializer = context.getBean(EventSerializer.class);
                var eventMessage = EventMessage.from(new SerializerTestEvent("value"), new Metadata(null, TIMESTAMP, null));

                var serialized = serializer.serialize(eventMessage);

                assertThat(new String(serialized.serializedPayload(), UTF_8)).contains("\n");
            }
        }
    }

    private ConfigurableApplicationContext createContext() {
        return new SpringApplicationBuilder(EventStoreChannelProcessingTestConfiguration.class)
            .profiles("event-store-processing")
            .web(NONE)
            .run();
    }

    private ConfigurableApplicationContext createContext(String property) {
        return new SpringApplicationBuilder(EventStoreChannelProcessingTestConfiguration.class)
            .profiles("event-store-processing")
            .properties(property)
            .web(NONE)
            .run();
    }

    private ConfigurableApplicationContext createContext(Class<?> additionalConfiguration) {
        return new SpringApplicationBuilder(EventStoreChannelProcessingTestConfiguration.class, additionalConfiguration)
            .profiles("event-store-processing")
            .properties(SCHEDULING_DISABLED)
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
        return queryToken(context, rs -> rs.getLong("last_processed_sequence_number"), processingGroup);
    }

    private Optional<Integer> tokenErrorCount(ConfigurableApplicationContext context) {
        return tokenErrorCount(context, ProcessingGroup.DEFAULT);
    }

    private Optional<Integer> tokenErrorCount(ConfigurableApplicationContext context, String processingGroup) {
        return queryToken(context, rs -> rs.getInt("error_count"), processingGroup);
    }

    private Optional<Long> tokenGapSequenceNumber(ConfigurableApplicationContext context) {
        return tokenGapSequenceNumber(context, ProcessingGroup.DEFAULT);
    }

    private Optional<Long> tokenGapSequenceNumber(ConfigurableApplicationContext context, String processingGroup) {
        return queryToken(context, rs -> rs.getLong("gap_sequence_number"), processingGroup);
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

    private <T> Optional<T> queryToken(ConfigurableApplicationContext context, ResultSetExtractor<T> extractor, String processingGroup) {
        var ds = context.getBean(DataSource.class);
        try (var conn = ds.getConnection(); var stmt = conn.prepareStatement("SELECT * FROM smd_token_store WHERE processing_group = ?")) {
            stmt.setString(1, processingGroup);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(extractor.extract(rs));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws Exception;
    }

    record SerializerTestEvent(String value) implements Event {
    }

    @Configuration
    static class CustomEventSerializerConfiguration {

        @Bean
        EventSerializer eventSerializer() {
            return new CustomEventSerializer();
        }
    }

    static class CustomEventSerializer implements EventSerializer {

        @Override
        public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
            throw new UnsupportedOperationException();
        }
    }

    @Configuration
    static class CustomEventTypeResolverConfiguration {

        @Bean
        EventTypeResolver eventTypeResolver() {
            return new CustomEventTypeResolver();
        }
    }

    static class CustomEventTypeResolver implements EventTypeResolver {

        static final String EVENT_TYPE = "custom-event";

        @Override
        public String eventTypeFor(Event event) throws EventTypeResolutionException {
            if (event instanceof SerializerTestEvent) {
                return EVENT_TYPE;
            }
            throw new EventTypeResolutionException("Unknown event: " + event.getClass().getName());
        }

        @Override
        public Class<? extends Event> eventClassFor(String eventType) throws EventTypeResolutionException {
            if (EVENT_TYPE.equals(eventType)) {
                return SerializerTestEvent.class;
            }
            throw new EventTypeResolutionException("Unknown event type: " + eventType);
        }
    }

    @Configuration
    static class EventSerializerJacksonModuleConfiguration {

        @Bean
        JacksonModule eventSerializerJacksonModule() {
            return new SimpleModule("test-event-serializer-module")
                .setMixInAnnotation(SerializerTestEvent.class, SerializerTestEventMixin.class);
        }

        private interface SerializerTestEventMixin {

            @JsonProperty("customValue")
            String value();
        }
    }

    @Configuration
    static class EventSerializerJsonMapperBuilderCustomizerConfiguration {

        @Bean
        JsonMapperBuilderCustomizer eventSerializerJsonMapperBuilderCustomizer() {
            return builder -> builder.enable(INDENT_OUTPUT);
        }
    }
}
