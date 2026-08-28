package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig.ProcessingConfig;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.store.SerializedEvent;
import app.dodb.smd.eventstore.store.TokenState;
import app.dodb.smd.spring.eventstore.EventStoreTestFixture.SequencedEvent;
import app.dodb.smd.spring.eventstore.processing.FailableTestEventHandler;
import app.dodb.smd.spring.eventstore.processing.SideEffectTestEventWithSubjectId;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import app.dodb.smd.test.EventChannelListenerStub;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.FAILED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.lang.Integer.MAX_VALUE;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;

class EventStoreProcessingIntegrationTest {

    private static final int BATCH_SIZE = 10;
    private static final String TEST_SUBJECT_ID = "testSubjectId";
    private static final String SIDE_EFFECT_TEST_SUBJECT_ID = "sideEffectTestSubjectId";
    private static final String PROCESSING_ID = "processingId";
    private static final String SEQUENCE_NUMBER = "sequenceNumber";
    private static final Instant TIMESTAMP = Instant.parse("2026-04-22T10:15:30Z");

    @Test
    void publishedEvent_isStoredHandledAndMarksTokenProcessed() {
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(FailableTestEventHandler.class);

            eventBus.publish(new TestEventWithSubjectId(TEST_SUBJECT_ID));

            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).hasSize(1);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                assertThat(fixture.storedEvents())
                    .extracting(SerializedEvent::eventType, SerializedEvent::subjectId)
                    .containsExactly(tuple(TestEventWithSubjectId.class.getName(), Optional.of(TEST_SUBJECT_ID)));
            });
        }
    }

    @Test
    void manualPoll_processesMultipleEventsInSingleBatch() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-batch-processing-group";
            var processingIds = new CopyOnWriteArraySet<String>();
            var processedSequences = new CopyOnWriteArrayList<String>();

            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(2L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(3L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            try (var channel = fixture.createChannel()) {
                channel.subscribe(new EventChannelListenerStub(processingGroup, eventMessage -> {
                    var metadata = eventMessage.metadata().properties();
                    processingIds.add(metadata.get(PROCESSING_ID));
                    processedSequences.add(metadata.get(SEQUENCE_NUMBER));
                }));

                await().untilAsserted(() -> {
                    assertThat(processingIds).hasSize(3).doesNotContainNull();
                    assertThat(processedSequences).containsExactly("1", "2", "3");
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(3L);
                });
            }
        }
    }

    @Test
    void laterEventFailure_keepsEarlierEventCommittedBeforeRecordingFailure() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-event-transaction-processing-group";
            createSideEffectsTable(fixture);

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

            try (var channel = fixture.createChannel(processingConfig)) {
                channel.subscribe(new EventChannelListenerStub(processingGroup, eventMessage -> {
                    var sequenceNumber = eventMessage.metadata().properties().get(SEQUENCE_NUMBER);
                    if ("1".equals(sequenceNumber)) {
                        insertTransactionalSideEffect(fixture, "event 1 committed");
                    } else if ("2".equals(sequenceNumber)) {
                        throw new IllegalStateException("event 2 fails");
                    }
                }));

                await().untilAsserted(() -> {
                    assertThat(sideEffectCount(fixture)).isOne();
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                    assertThat(fixture.eventSequenceState(processingGroup, TEST_SUBJECT_ID)
                        .map(EventSequenceState::status)).contains(FAILED);
                    assertThat(fixture.eventSequenceState(processingGroup, TEST_SUBJECT_ID)
                        .map(EventSequenceState::errorCount)).contains(1);
                });
            }
        }
    }

    @Test
    void gapDetected_marksGapThenSkipsAfterTimeout() {
        try (var fixture = eventStoreTestFixture().start()) {
            var handler = fixture.bean(FailableTestEventHandler.class);

            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID)),
                new SequencedEvent<>(3L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            await().untilAsserted(() ->
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::gapSequenceNumber)).contains(2L)
            );

            await().untilAsserted(() -> {
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedSequenceNumber)).contains(3L);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::gapSequenceNumber)).isEmpty();
            });

            assertThat(handler.getHandledEvents()).hasSize(2);
        }
    }

    @Test
    void handlerFailure_recordsErrorThenRetriesSuccessfully() {
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(FailableTestEventHandler.class);

            handler.failNextNAttempts(1);
            eventBus.publish(new TestEventWithSubjectId(TEST_SUBJECT_ID));

            await().untilAsserted(() ->
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, TEST_SUBJECT_ID)
                    .map(EventSequenceState::errorCount)).contains(1)
            );

            await().untilAsserted(() -> {
                assertThat(handler.getHandledEvents()).hasSize(1);
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, TEST_SUBJECT_ID)
                    .map(EventSequenceState::errorCount)).contains(0);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
            });
        }
    }

    @Test
    void handlerFailure_rollsBackDatabaseSideEffects() {
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            createSideEffectsTable(fixture);

            eventBus.publish(new SideEffectTestEventWithSubjectId(SIDE_EFFECT_TEST_SUBJECT_ID));

            await().untilAsserted(() -> {
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, SIDE_EFFECT_TEST_SUBJECT_ID)
                    .map(EventSequenceState::errorCount))
                    .hasValueSatisfying(errorCount -> assertThat(errorCount).isGreaterThanOrEqualTo(1));
                assertThat(sideEffectCount(fixture)).isZero();
            });
        }
    }

    @Test
    void handlerFailure_marksEventAbandonedAfterRetriesExhausted() {
        try (var fixture = eventStoreTestFixture().start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(FailableTestEventHandler.class);

            handler.failNextNAttempts(MAX_VALUE);
            eventBus.publish(new TestEventWithSubjectId(TEST_SUBJECT_ID));

            await().untilAsserted(() -> {
                assertThat(fixture.eventSequenceState(ProcessingGroup.DEFAULT, TEST_SUBJECT_ID)
                    .map(EventSequenceState::status)).contains(ABANDONED);
                assertThat(fixture.tokenState(ProcessingGroup.DEFAULT)
                    .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
            });

            assertThat(handler.getHandledEvents()).isEmpty();
        }
    }

    @Test
    void sameProcessingGroup_whenTwoChannelsPollConcurrently_processesEventOnce() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-concurrent-processing-group";
            var handledCount = new AtomicInteger();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId(TEST_SUBJECT_ID))
            );

            try (var firstChannel = fixture.createChannel();
                 var secondChannel = fixture.createChannel()) {
                var listener = new EventChannelListenerStub(processingGroup, _ -> {
                    handledCount.incrementAndGet();
                    sleep(ofMillis(300));
                });

                firstChannel.subscribe(listener);
                secondChannel.subscribe(listener);

                await().untilAsserted(() -> {
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                    assertThat(handledCount).hasValue(1);
                });

                await().during(ofMillis(500)).untilAsserted(() ->
                    assertThat(handledCount).hasValue(1)
                );
            }
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

    private void createSideEffectsTable(EventStoreTestFixture fixture) {
        var dataSource = fixture.bean(DataSource.class);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS event_handler_side_effects
                (
                    id BIGSERIAL PRIMARY KEY,
                    description VARCHAR(255) NOT NULL
                )
                """);
            statement.execute("DELETE FROM event_handler_side_effects");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int sideEffectCount(EventStoreTestFixture fixture) {
        var dataSource = fixture.bean(DataSource.class);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM event_handler_side_effects")) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertTransactionalSideEffect(EventStoreTestFixture fixture, String description) {
        var dataSource = fixture.bean(DataSource.class);
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.prepareStatement("INSERT INTO event_handler_side_effects (description) VALUES (?)")) {
            statement.setString(1, description);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
