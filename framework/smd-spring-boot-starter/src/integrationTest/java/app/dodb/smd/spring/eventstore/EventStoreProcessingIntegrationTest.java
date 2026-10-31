package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.delivery.ProcessingMetadata;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.EventStoreMetadata;
import app.dodb.smd.eventstore.processing.EventSequenceId;
import app.dodb.smd.eventstore.processing.EventSequenceState;
import app.dodb.smd.eventstore.processing.TokenState;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.spring.eventstore.EventStoreTestFixture.SequencedEvent;
import app.dodb.smd.spring.eventstore.processing.FailableTestEventHandler;
import app.dodb.smd.spring.eventstore.processing.SideEffectTestEventWithSubjectId;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.processing.EventSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.processing.EventSequenceStatus.FAILED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.lang.Integer.MAX_VALUE;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

class EventStoreProcessingIntegrationTest {

    private static final int BATCH_SIZE = 10;
    private static final String TEST_SUBJECT_ID = "testSubjectId";
    private static final String SIDE_EFFECT_TEST_SUBJECT_ID = "sideEffectTestSubjectId";
    private static final String PROCESSING_ID = ProcessingMetadata.PROCESSING_ID;
    private static final String GLOBAL_POSITION = EventStoreMetadata.GLOBAL_POSITION;

    @Test
    void publish_withScheduledDelivery_storesEventHandlesItAndMarksTokenProcessed() {
        // Given
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(FailableTestEventHandler.class);

            // When
            eventBus.publish(new TestEventWithSubjectId(TEST_SUBJECT_ID));

            // Then
            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).hasSize(1);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);
                assertThat(fixture.storedEvents())
                    .extracting(StoredEvent::eventType, StoredEvent::subject, StoredEvent::subjectVersion)
                    .containsExactly(tuple(TestEventWithSubjectId.class.getName(), Optional.of(new Subject(TestEventWithSubjectId.SUBJECT_TYPE, TEST_SUBJECT_ID)), Optional.empty()));
            });
        }
    }

    @Test
    void poll_withMultipleEvents_processesSingleBatch() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-batch-processing-group";
            var processingIds = new CopyOnWriteArraySet<String>();
            var processedGlobalPositions = new CopyOnWriteArrayList<String>();

            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(2L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(3L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            var subscriber = new EventSubscriberStub(processingGroup, eventMessage -> {
                var metadata = eventMessage.metadata().transientProperties();
                processingIds.add(metadata.get(PROCESSING_ID));
                processedGlobalPositions.add(metadata.get(GLOBAL_POSITION));
            });

            try (var eventStore = fixture.createEventStore(scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                // When
                scheduler.poll();

                // Then
                assertThat(processingIds).hasSize(3).doesNotContainNull();
                assertThat(processedGlobalPositions).containsExactly("1", "2", "3");
                assertThat(fixture.tokenState(processingGroup)
                    .flatMap(TokenState::lastProcessedPosition)).contains(3L);
            }
        }
    }

    @Test
    void poll_afterRestart_resumesWithoutReprocessingCommittedEvents() throws Exception {
        // Given
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "restart-processing-group";
            var processedGlobalPositions = new CopyOnWriteArrayList<String>();
            var listener = new EventSubscriberStub(processingGroup, eventMessage ->
                processedGlobalPositions.add(eventMessage.metadata().transientProperties().get(GLOBAL_POSITION))
            );

            fixture.storeEvents(new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID)));
            var firstScheduler = new ManualPollingScheduler();
            try (var eventStore = fixture.createEventStore(firstScheduler);
                 var _ = eventStore.subscribe(listener)) {
                firstScheduler.poll();
                assertThat(fixture.tokenState(processingGroup)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);
            }

            fixture.storeEvents(new SequencedEvent<>(2L, new TestEventWithSubjectId(TEST_SUBJECT_ID)));
            var restartedScheduler = new ManualPollingScheduler();
            try (var eventStore = fixture.createEventStore(restartedScheduler);
                 var _ = eventStore.subscribe(listener)) {
                // When
                restartedScheduler.poll();

                // Then
                assertThat(processedGlobalPositions).containsExactly("1", "2");
                assertThat(fixture.tokenState(processingGroup)
                    .flatMap(TokenState::lastProcessedPosition)).contains(2L);
            }
        }
    }

    @Test
    void poll_whenLaterEventFails_keepsEarlierEventCommittedBeforeRecordingFailure() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-event-transaction-processing-group";
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            createSideEffectsTable(jdbc);

            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(2L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            var processingConfig = ProcessingConfig.withoutDefaults()
                .maxRetries(5)
                .batchSize(BATCH_SIZE)
                .retryBackoffStrategy(fixed(Duration.ofDays(1)))
                .gapTimeout(ofSeconds(1))
                .build();

            var subscriber = new EventSubscriberStub(processingGroup, eventMessage -> {
                var globalPosition = eventMessage.metadata().transientProperties().get(GLOBAL_POSITION);
                if ("1".equals(globalPosition)) {
                    assertThat(jdbc.update("INSERT INTO event_handler_side_effects (description) VALUES (?)",
                        "event 1 committed")).isOne();
                } else if ("2".equals(globalPosition)) {
                    throw new IllegalStateException("event 2 fails");
                }
            });

            try (var eventStore = fixture.createEventStore(processingConfig, scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                scheduler.poll();
                assertThat(sideEffects(jdbc)).containsExactly("event 1 committed");
                assertThat(fixture.tokenState(processingGroup)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);

                // When
                scheduler.poll();

                // Then
                assertThat(sideEffects(jdbc)).containsExactly("event 1 committed");
                assertThat(fixture.tokenState(processingGroup)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);
                assertThat(fixture.eventSequenceState(processingGroup, new EventSequenceId.OfSubject(new Subject(TestEventWithSubjectId.SUBJECT_TYPE, TEST_SUBJECT_ID))))
                    .hasValueSatisfying(state -> {
                        assertThat(state.status()).isEqualTo(FAILED);
                        assertThat(state.errorCount()).isOne();
                    });
            }
        }
    }

    @Test
    void poll_withSequenceGap_marksGapThenSkipsAfterTimeout() {
        // Given
        try (var fixture = eventStoreTestFixture().start()) {
            var handler = fixture.bean(FailableTestEventHandler.class);

            // When
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(3L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            // Then
            await().untilAsserted(() ->
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::gapPosition)).contains(2L)
            );

            await().untilAsserted(() -> {
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedPosition)).contains(3L);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::gapPosition)).isEmpty();
            });

            assertThat(handler.getHandledEvents()).hasSize(2);
        }
    }

    @Test
    void poll_whenHandlerFailsOnce_retriesSuccessfully() {
        // Given
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(FailableTestEventHandler.class);

            handler.failNextNAttempts(1);

            // When
            eventBus.publish(new TestEventWithSubjectId(TEST_SUBJECT_ID));

            // Then
            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).hasSize(1);
                assertThat(handler.getAttemptedErrorCounts()).containsExactly("0", "1");
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, new EventSequenceId.OfSubject(new Subject(TestEventWithSubjectId.SUBJECT_TYPE, TEST_SUBJECT_ID)))
                    .map(EventSequenceState::errorCount)).contains(0);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);
            });
        }
    }

    @Test
    void poll_whenHandlerFails_rollsBackDatabaseSideEffects() {
        // Given
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            createSideEffectsTable(jdbc);

            // When
            eventBus.publish(new SideEffectTestEventWithSubjectId(SIDE_EFFECT_TEST_SUBJECT_ID));

            // Then
            await().untilAsserted(() -> {
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, new EventSequenceId.OfSubject(new Subject(TestEventWithSubjectId.SUBJECT_TYPE, SIDE_EFFECT_TEST_SUBJECT_ID)))
                    .flatMap(EventSequenceState::lastErrorMessage))
                    .hasValueSatisfying(error -> assertThat(error).contains("Simulated failure after side effect"));
                assertThat(sideEffects(jdbc)).isEmpty();
            });
        }
    }

    @Test
    void poll_whenRetriesAreExhausted_marksEventAbandoned() {
        // Given
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(FailableTestEventHandler.class);

            handler.failNextNAttempts(MAX_VALUE);

            // When
            eventBus.publish(new TestEventWithSubjectId(TEST_SUBJECT_ID));

            // Then
            await().untilAsserted(() -> {
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, new EventSequenceId.OfSubject(new Subject(TestEventWithSubjectId.SUBJECT_TYPE, TEST_SUBJECT_ID)))
                    .map(EventSequenceState::status)).contains(ABANDONED);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);
            });

            assertThat(handler.getHandledEvents()).isEmpty();
        }
    }

    @Test
    void poll_withConcurrentStoresInSameProcessingGroup_processesEventOnce() throws Exception {
        // Given
        var firstScheduler = new ManualPollingScheduler();
        var secondScheduler = new ManualPollingScheduler();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-concurrent-processing-group";
            var handledCount = new AtomicInteger();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            var listener = new EventSubscriberStub(processingGroup, _ -> {
                handledCount.incrementAndGet();
                entered.countDown();
                try {
                    assertThat(release.await(5, SECONDS)).as("handler released").isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Handler interrupted while holding the token", e);
                }
            });

            try (var firstEventStore = fixture.createEventStore(firstScheduler);
                 var secondEventStore = fixture.createEventStore(secondScheduler);
                 var _ = firstEventStore.subscribe(listener);
                 var _ = secondEventStore.subscribe(listener)) {
                var firstPoll = firstScheduler.pollAsync();
                try {
                    assertThat(entered.await(5, SECONDS)).as("first store holds the token").isTrue();

                    // When
                    secondScheduler.poll();

                    // Then
                    assertThat(handledCount).hasValue(1);
                } finally {
                    release.countDown();
                }
                firstPoll.get(5, SECONDS);

                // When
                firstScheduler.poll();
                secondScheduler.poll();

                // Then
                assertThat(handledCount).hasValue(1);
                assertThat(fixture.tokenState(processingGroup)
                    .flatMap(TokenState::lastProcessedPosition)).contains(1L);
            }
        }
    }

    private void createSideEffectsTable(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE event_handler_side_effects
            (
                id BIGSERIAL PRIMARY KEY,
                description VARCHAR(255) NOT NULL
            )
            """);
    }

    private List<String> sideEffects(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT description FROM event_handler_side_effects ORDER BY id", String.class);
    }
}
