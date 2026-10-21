package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.ResetResult.Reset;
import app.dodb.smd.eventstore.ResetResult.TokenClaimed;
import app.dodb.smd.eventstore.ResetResult.UnknownProcessingGroup;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.spring.eventstore.EventStoreTestFixture.SequencedEvent;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;

import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ACTIVE;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class EventStoreResetIntegrationTest {

    private static final String PROCESSING_GROUP = "reset-processing-group";
    private static final String SEQUENCE_NUMBER = "sequenceNumber";
    private static final String REPLAY = "replay";

    @Test
    void reset_whilePolling_replaysEveryEventOnce() {
        // Given
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var deliveries = new CopyOnWriteArrayList<Delivery>();
            fixture.storeEvents(sequencedEvents(1, 25));

            try (var eventStore = fixture.createEventStore(newSingleThreadScheduledExecutor());
                 var _ = eventStore.subscribe(recordingSubscriber(deliveries))) {
                await().untilAsserted(() -> assertThat(position(fixture)).contains(25L));

                // When
                var result = eventStore.reset(PROCESSING_GROUP);

                // Then
                assertThat(result).isEqualTo(new Reset(PROCESSING_GROUP, Optional.of(25L), Optional.of(25L)));
                await().untilAsserted(() -> {
                    assertThat(position(fixture)).contains(25L);
                    assertThat(fixture.tokenState(PROCESSING_GROUP).flatMap(TokenState::replayUntilSequenceNumber)).isEmpty();
                });
                assertThat(deliveries.stream().filter(Delivery::replay).map(Delivery::sequenceNumber))
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, 25).boxed().toList());
                assertThat(deliveries.stream().filter(delivery -> !delivery.replay()).map(Delivery::sequenceNumber))
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, 25).boxed().toList());
            }
        }
    }

    @Test
    void reset_whileBatchInFlight_waitsForBatchCommitThenReplaysIt() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking = new AtomicBoolean(true);
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            var deliveries = new CopyOnWriteArrayList<Delivery>();
            fixture.storeEvents(new SequencedEvent<>(1L, new TestEventWithSubjectId("ticket")));
            var subscriber = new EventSubscriberStub(PROCESSING_GROUP, message -> {
                if (blocking.getAndSet(false)) {
                    entered.countDown();
                    awaitRelease(release);
                }
                deliveries.add(Delivery.from(message.metadata().properties()));
            });

            try (var eventStore = fixture.createEventStore(fixture.defaultProcessingConfigBuilder()
                .resetLockTimeout(ofSeconds(30))
                .build(), scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                var batch = scheduler.pollAsync();
                assertThat(entered.await(5, SECONDS)).as("batch holds the token").isTrue();

                // When
                var reset = CompletableFuture.supplyAsync(() -> eventStore.reset(PROCESSING_GROUP));
                await().untilAsserted(() -> assertThat(lockWaiters(jdbc)).isPositive());
                release.countDown();
                batch.get(5, SECONDS);

                // Then
                assertThat(reset.get(5, SECONDS)).isEqualTo(new Reset(PROCESSING_GROUP, Optional.of(1L), Optional.of(1L)));

                // When
                scheduler.poll();

                // Then
                assertThat(deliveries).containsExactly(new Delivery(1L, false), new Delivery(1L, true));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void reset_withAbandonedSubject_retriesSubject() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        var failing = new AtomicBoolean(true);
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var deliveries = new CopyOnWriteArrayList<Delivery>();
            fixture.storeEvents(new SequencedEvent<>(1L, new TestEventWithSubjectId("ticket")));
            var subscriber = new EventSubscriberStub(PROCESSING_GROUP, message -> {
                if (failing.get()) {
                    throw new IllegalStateException("handler bug");
                }
                deliveries.add(Delivery.from(message.metadata().properties()));
            });

            try (var eventStore = fixture.createEventStore(fixture.defaultProcessingConfigBuilder().maxRetries(0).build(), scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                scheduler.poll();
                assertThat(fixture.eventSequenceState(PROCESSING_GROUP, "ticket")
                    .map(EventSequenceState::status)).contains(ABANDONED);
                failing.set(false);

                // When
                eventStore.reset(PROCESSING_GROUP);
                scheduler.poll();

                // Then
                assertThat(deliveries).containsExactly(new Delivery(1L, true));
                assertThat(fixture.eventSequenceState(PROCESSING_GROUP, "ticket")
                    .map(EventSequenceState::status)).contains(ACTIVE);
            }
        }
    }

    @Test
    void reset_whenResetActionFails_rollsBackTokenSequenceStateAndActionChanges() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            jdbc.execute("CREATE TABLE reset_view (sequence_number BIGINT PRIMARY KEY)");
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId("ticket")),
                new SequencedEvent<>(2L, new TestEventWithSubjectId("ticket"))
            );
            var subscriber = new EventSubscriberStub(PROCESSING_GROUP, message ->
                jdbc.update("INSERT INTO reset_view (sequence_number) VALUES (?)",
                    Long.parseLong(message.metadata().properties().get(SEQUENCE_NUMBER))));
            var failure = new IllegalStateException("rebuild preparation failed");

            try (var eventStore = fixture.createEventStore(scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                scheduler.poll();

                // When
                assertThatThrownBy(() -> eventStore.reset(PROCESSING_GROUP, () -> {
                    jdbc.execute("TRUNCATE reset_view");
                    throw failure;
                })).isSameAs(failure);

                // Then
                assertThat(fixture.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
                    assertThat(state.lastProcessedSequenceNumber()).contains(2L);
                    assertThat(state.replayUntilSequenceNumber()).isEmpty();
                });
                assertThat(fixture.eventSequenceState(PROCESSING_GROUP, "ticket")
                    .flatMap(EventSequenceState::lastProcessedSequenceNumber)).contains(2L);
                assertThat(jdbc.queryForList("SELECT sequence_number FROM reset_view ORDER BY sequence_number", Long.class))
                    .containsExactly(1L, 2L);
            }
        }
    }

    @Test
    void reset_withResetAction_commitsActionWithReset() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            jdbc.execute("CREATE TABLE reset_view (sequence_number BIGINT PRIMARY KEY)");
            fixture.storeEvents(new SequencedEvent<>(1L, new TestEventWithSubjectId("ticket")));
            var subscriber = new EventSubscriberStub(PROCESSING_GROUP, message ->
                jdbc.update("INSERT INTO reset_view (sequence_number) VALUES (?)",
                    Long.parseLong(message.metadata().properties().get(SEQUENCE_NUMBER))));

            try (var eventStore = fixture.createEventStore(scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                scheduler.poll();

                // When
                eventStore.reset(PROCESSING_GROUP, () -> jdbc.execute("TRUNCATE reset_view"));
                var viewAfterReset = jdbc.queryForList("SELECT sequence_number FROM reset_view", Long.class);
                scheduler.poll();

                // Then
                assertThat(viewAfterReset).isEmpty();
                assertThat(jdbc.queryForList("SELECT sequence_number FROM reset_view", Long.class)).containsExactly(1L);
            }
        }
    }

    @Test
    void reset_whileTokenLockedPastTimeout_returnsTokenClaimedAndChangesNothing() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId("ticket")),
                new SequencedEvent<>(2L, new TestEventWithSubjectId("ticket"))
            );
            var subscriber = new EventSubscriberStub(PROCESSING_GROUP, message -> {
                if ("2".equals(message.metadata().properties().get(SEQUENCE_NUMBER))) {
                    entered.countDown();
                    awaitRelease(release);
                }
            });

            try (var eventStore = fixture.createEventStore(fixture.defaultProcessingConfigBuilder()
                .batchSize(1)
                .build(), scheduler);
                 var _ = eventStore.subscribe(subscriber)) {
                scheduler.poll();
                var batch = scheduler.pollAsync();
                assertThat(entered.await(5, SECONDS)).as("batch holds the token").isTrue();

                try {
                    // When
                    var result = eventStore.reset(PROCESSING_GROUP, () -> {
                        throw new AssertionError("reset action must not run without the token");
                    });

                    // Then
                    assertThat(result).isEqualTo(new TokenClaimed(PROCESSING_GROUP));
                } finally {
                    release.countDown();
                }
                batch.get(5, SECONDS);
                assertThat(fixture.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
                    assertThat(state.lastProcessedSequenceNumber()).contains(2L);
                    assertThat(state.replayUntilSequenceNumber()).isEmpty();
                });
                assertThat(fixture.eventSequenceState(PROCESSING_GROUP, "ticket")
                    .flatMap(EventSequenceState::lastProcessedSequenceNumber)).contains(2L);
            }
        }
    }

    @Test
    void poll_afterReset_setsReplayPropertyUpToPreviousPosition() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var deliveries = new CopyOnWriteArrayList<Delivery>();
            fixture.storeEvents(sequencedEvents(1, 2));

            try (var eventStore = fixture.createEventStore(scheduler);
                 var _ = eventStore.subscribe(recordingSubscriber(deliveries))) {
                scheduler.poll();
                eventStore.reset(PROCESSING_GROUP);
                fixture.storeEvents(sequencedEvents(3, 3));
                deliveries.clear();

                // When
                scheduler.poll();

                // Then
                assertThat(deliveries).containsExactly(
                    new Delivery(1L, true),
                    new Delivery(2L, true),
                    new Delivery(3L, false)
                );
                assertThat(fixture.tokenState(PROCESSING_GROUP).flatMap(TokenState::replayUntilSequenceNumber)).isEmpty();
            }
        }
    }

    @Test
    void poll_belowReplayBoundary_skipsMissingPositionsWithoutGapTimeout() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var jdbc = new JdbcTemplate(fixture.bean(DataSource.class));
            var deliveries = new CopyOnWriteArrayList<Delivery>();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId("ticket")),
                new SequencedEvent<>(4L, new TestEventWithSubjectId("ticket"))
            );
            jdbc.update("INSERT INTO smd_token_store (processing_group, last_processed_sequence_number) VALUES (?, 4)", PROCESSING_GROUP);

            try (var eventStore = fixture.createEventStore(fixture.defaultProcessingConfigBuilder()
                .gapTimeout(ofHours(1))
                .build(), scheduler);
                 var _ = eventStore.subscribe(recordingSubscriber(deliveries))) {
                eventStore.reset(PROCESSING_GROUP);

                // When
                scheduler.poll();

                // Then
                assertThat(deliveries).containsExactly(new Delivery(1L, true), new Delivery(4L, true));
                assertThat(fixture.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
                    assertThat(state.lastProcessedSequenceNumber()).contains(4L);
                    assertThat(state.gapSequenceNumber()).isEmpty();
                });
            }
        }
    }

    @Test
    void subscribe_withBacklog_drainsItWithoutWaitingForPollingDelay() {
        // Given
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var deliveries = new CopyOnWriteArrayList<Delivery>();
            fixture.storeEvents(sequencedEvents(1, 35));

            // When
            try (var eventStore = fixture.createEventStore(fixture.defaultProcessingConfigBuilder().build(),
                newSingleThreadScheduledExecutor(), ofHours(1));
                 var _ = eventStore.subscribe(recordingSubscriber(deliveries))) {

                // Then
                await().atMost(ofSeconds(10)).untilAsserted(() ->
                    assertThat(deliveries).extracting(Delivery::sequenceNumber)
                        .containsExactlyElementsOf(LongStream.rangeClosed(1, 35).boxed().toList()));
            }
        }
    }

    @Test
    void reset_withUnknownProcessingGroup_returnsUnknownAndCreatesNoToken() {
        // Given
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var eventStore = fixture.bean(EventStore.class);

            // When
            var result = eventStore.reset("unknown-processing-group");

            // Then
            assertThat(result).isEqualTo(new UnknownProcessingGroup("unknown-processing-group"));
            assertThat(fixture.tokenState("unknown-processing-group")).isEmpty();
        }
    }

    @Test
    void reset_withConfiguredGroupWithoutToken_createsTokenAtStart() {
        // Given
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            var eventStore = fixture.bean(EventStore.class);

            // When
            var result = eventStore.reset(ProcessingGroup.DEFAULT);

            // Then
            assertThat(result).isEqualTo(new Reset(ProcessingGroup.DEFAULT, Optional.empty(), Optional.empty()));
            assertThat(fixture.tokenState(ProcessingGroup.DEFAULT).flatMap(TokenState::lastProcessedSequenceNumber)).contains(0L);
        }
    }

    @Test
    void progress_afterReset_reportsPositionHeadAndReplayBoundary() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start()) {
            fixture.storeEvents(sequencedEvents(1, 3));

            try (var eventStore = fixture.createEventStore(scheduler);
                 var _ = eventStore.subscribe(recordingSubscriber(new CopyOnWriteArrayList<>()))) {
                scheduler.poll();
                eventStore.reset(PROCESSING_GROUP);

                // When
                var progress = eventStore.progress(PROCESSING_GROUP);

                // Then
                assertThat(progress).hasValueSatisfying(value -> {
                    assertThat(value.position()).isZero();
                    assertThat(value.head()).isEqualTo(3L);
                    assertThat(value.replayUntil()).contains(3L);
                    assertThat(value.replaying()).isTrue();
                });
            }
        }
    }

    @Test
    void resetLockTimeoutProperty_configuresEventStore() {
        // Given
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED, "smd.event-store.processing.reset-lock-timeout=7s")
            .start()) {

            // When
            var processingConfig = fixture.bean(EventStoreConfig.class).getProcessingConfig();

            // Then
            assertThat(processingConfig.getResetLockTimeout()).isEqualTo(ofSeconds(7));
        }
    }

    private static Optional<Long> position(EventStoreTestFixture fixture) {
        return fixture.tokenState(PROCESSING_GROUP).flatMap(TokenState::lastProcessedSequenceNumber);
    }

    private static int lockWaiters(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
    }

    private static SequencedEvent<?>[] sequencedEvents(long from, long to) {
        return LongStream.rangeClosed(from, to)
            .mapToObj(sequenceNumber -> new SequencedEvent<>(sequenceNumber, new TestEventWithSubjectId("ticket-" + sequenceNumber % 3)))
            .toArray(SequencedEvent<?>[]::new);
    }

    private static EventSubscriberStub recordingSubscriber(List<Delivery> deliveries) {
        return new EventSubscriberStub(PROCESSING_GROUP, message -> deliveries.add(Delivery.from(message.metadata().properties())));
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(10, SECONDS)).as("handler released").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Handler interrupted while holding the token", e);
        }
    }

    private record Delivery(long sequenceNumber, boolean replay) {

        static Delivery from(Map<String, String> properties) {
            return new Delivery(Long.parseLong(properties.get(SEQUENCE_NUMBER)), Boolean.parseBoolean(properties.get(REPLAY)));
        }
    }
}
