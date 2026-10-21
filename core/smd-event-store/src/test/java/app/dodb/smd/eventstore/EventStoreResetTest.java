package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import app.dodb.smd.eventstore.ResetResult.Reset;
import app.dodb.smd.eventstore.ResetResult.UnknownProcessingGroup;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.test.EventSubscriberStub;
import app.dodb.smd.test.NoOpTransactionProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ACTIVE;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStoreResetTest {

    private static final String PROCESSING_GROUP = "ticket-view";
    private static final String SUBJECT_ID = "ticket-1";

    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final InMemoryTokenStore tokenStore = new InMemoryTokenStore();
    private final InMemoryEventSubjectSequenceStore eventSequenceStore = new InMemoryEventSubjectSequenceStore();
    private final JacksonEventSerializer eventSerializer = new JacksonEventSerializer(JsonMapper.builder()
        .addModule(new SMDJacksonModule())
        .build());
    private final EventStoreConfig config = EventStoreConfig.withoutDefaults()
        .transactionProvider(new NoOpTransactionProvider())
        .eventStorage(eventStorage)
        .eventSerializer(eventSerializer)
        .tokenStore(tokenStore)
        .eventSequenceStore(eventSequenceStore)
        .schedulingConfig(SchedulingConfig.withoutDefaults()
            .enabled(false)
            .scheduler(newSingleThreadScheduledExecutor())
            .initialDelay(ZERO)
            .pollingDelay(ofSeconds(1))
            .build())
        .processingConfig(ProcessingConfig.withDefaults()
            .maxRetries(0)
            .retryBackoffStrategy(fixed(ZERO))
            .gapTimeout(ofMinutes(5))
            .resetLockTimeout(ofSeconds(1))
            .build())
        .build();
    private final EventStore eventStore = new EventStore(config);
    private final EventStoreTokenProcessor tokenProcessor = new EventStoreTokenProcessor(config);
    private final AtomicBoolean failing = new AtomicBoolean();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final EventSubscriberStub subscriber = new EventSubscriberStub(PROCESSING_GROUP, message -> {
        if (failing.get()) {
            throw new IllegalStateException("handler failure");
        }
        var properties = message.metadata().properties();
        deliveries.add(new Delivery(Long.parseLong(properties.get("sequenceNumber")), properties.get("replay")));
    });

    @AfterEach
    void closeEventStore() {
        eventStore.close();
    }

    @Test
    void reset_afterProcessing_redeliversEveryEventAsReplay() {
        storeEvents(3);
        eventStore.outlet().subscribe(subscriber);
        tokenProcessor.poll(subscriber);
        deliveries.clear();

        var result = eventStore.reset(PROCESSING_GROUP);
        storeEvents(1);
        tokenProcessor.poll(subscriber);

        assertThat(result).isEqualTo(new Reset(PROCESSING_GROUP, Optional.of(3L), Optional.of(3L)));
        assertThat(deliveries).containsExactly(
            new Delivery(1L, "true"),
            new Delivery(2L, "true"),
            new Delivery(3L, "true"),
            new Delivery(4L, "false")
        );
        assertThat(tokenStore.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedSequenceNumber()).contains(4L);
            assertThat(state.replayUntilSequenceNumber()).isEmpty();
        });
    }

    @Test
    void poll_withoutReset_deliversEventsAsNotReplayed() {
        storeEvents(1);

        tokenProcessor.poll(subscriber);

        assertThat(deliveries).containsExactly(new Delivery(1L, "false"));
    }

    @Test
    void reset_withAbandonedSubject_retriesSubject() {
        storeEvents(1);
        failing.set(true);
        tokenProcessor.poll(subscriber);
        assertThat(eventSequenceStore.eventSequenceState(PROCESSING_GROUP, SUBJECT_ID)
            .map(EventSequenceState::status)).contains(ABANDONED);
        failing.set(false);

        eventStore.reset(PROCESSING_GROUP);
        tokenProcessor.poll(subscriber);

        assertThat(deliveries).containsExactly(new Delivery(1L, "true"));
        assertThat(eventSequenceStore.eventSequenceState(PROCESSING_GROUP, SUBJECT_ID)
            .map(EventSequenceState::status)).contains(ACTIVE);
    }

    @Test
    void reset_whenResetActionFails_propagatesExceptionAndKeepsState() {
        storeEvents(2);
        tokenProcessor.poll(subscriber);
        var failure = new IllegalStateException("truncate failed");

        assertThatThrownBy(() -> eventStore.reset(PROCESSING_GROUP, () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(tokenStore.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedSequenceNumber()).contains(2L);
            assertThat(state.replayUntilSequenceNumber()).isEmpty();
        });
        assertThat(eventSequenceStore.eventSequenceState(PROCESSING_GROUP, SUBJECT_ID)
            .flatMap(EventSequenceState::lastProcessedSequenceNumber)).contains(2L);
    }

    @Test
    void reset_withResetAction_runsActionOnce() {
        storeEvents(1);
        tokenProcessor.poll(subscriber);
        var actionRuns = new CopyOnWriteArrayList<String>();

        eventStore.reset(PROCESSING_GROUP, () -> actionRuns.add("truncated"));

        assertThat(actionRuns).containsExactly("truncated");
    }

    @Test
    void reset_withUnknownProcessingGroup_returnsUnknownWithoutRunningActionOrCreatingToken() {
        var actionRan = new AtomicBoolean();

        var result = eventStore.reset("tiket-view", () -> actionRan.set(true));

        assertThat(result).isEqualTo(new UnknownProcessingGroup("tiket-view"));
        assertThat(actionRan).isFalse();
        assertThat(tokenStore.tokenState("tiket-view")).isEmpty();
    }

    @Test
    void reset_withSubscribedGroupWithoutToken_resetsWithoutReplayBoundary() {
        eventStore.outlet().subscribe(subscriber);

        var result = eventStore.reset(PROCESSING_GROUP);

        assertThat(result).isEqualTo(new Reset(PROCESSING_GROUP, Optional.empty(), Optional.empty()));
        assertThat(tokenStore.tokenState(PROCESSING_GROUP).flatMap(TokenState::lastProcessedSequenceNumber)).contains(0L);
    }

    @Test
    void reset_afterSubscriptionClosedWithoutToken_returnsUnknown() {
        eventStore.outlet().subscribe(subscriber).close();

        var result = eventStore.reset(PROCESSING_GROUP);

        assertThat(result).isEqualTo(new UnknownProcessingGroup(PROCESSING_GROUP));
    }

    @Test
    void reset_duringReplay_keepsFurthestReplayBoundary() {
        storeEvents(3);
        tokenProcessor.poll(subscriber);
        eventStore.reset(PROCESSING_GROUP);

        var result = eventStore.reset(PROCESSING_GROUP);

        assertThat(result).isEqualTo(new Reset(PROCESSING_GROUP, Optional.of(0L), Optional.of(3L)));
    }

    @Test
    void poll_belowReplayBoundary_skipsMissingPositionsWithoutWaitingForGapTimeout() {
        storeEvent(1L);
        storeEvent(3L);
        tokenStore.claimToken(PROCESSING_GROUP).orElseThrow().markProcessed(3L);
        eventStore.reset(PROCESSING_GROUP);

        tokenProcessor.poll(subscriber);

        assertThat(deliveries).containsExactly(new Delivery(1L, "true"), new Delivery(3L, "true"));
        assertThat(tokenStore.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedSequenceNumber()).contains(3L);
            assertThat(state.gapSequenceNumber()).isEmpty();
        });
    }

    @Test
    void poll_aboveReplayBoundary_waitsForGapTimeout() {
        storeEvent(1L);
        storeEvent(3L);
        tokenStore.claimToken(PROCESSING_GROUP).orElseThrow().markProcessed(1L);
        eventStore.reset(PROCESSING_GROUP);

        tokenProcessor.poll(subscriber);

        assertThat(deliveries).containsExactly(new Delivery(1L, "true"));
        assertThat(tokenStore.tokenState(PROCESSING_GROUP)).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedSequenceNumber()).contains(1L);
            assertThat(state.gapSequenceNumber()).contains(2L);
        });
    }

    @Test
    void progress_withoutToken_returnsEmpty() {
        assertThat(eventStore.progress(PROCESSING_GROUP)).isEmpty();
    }

    @Test
    void progress_afterReset_reportsReplay() {
        storeEvents(3);
        tokenProcessor.poll(subscriber);
        eventStore.reset(PROCESSING_GROUP);

        var progress = eventStore.progress(PROCESSING_GROUP);

        assertThat(progress).contains(new ProcessingProgress(PROCESSING_GROUP, 0L, 3L, Optional.of(3L)));
        assertThat(progress).hasValueSatisfying(value -> {
            assertThat(value.remaining()).isEqualTo(3L);
            assertThat(value.replaying()).isTrue();
        });
    }

    @Test
    void progress_afterReplayFinished_reportsCaughtUp() {
        storeEvents(3);
        tokenProcessor.poll(subscriber);
        eventStore.reset(PROCESSING_GROUP);
        tokenProcessor.poll(subscriber);

        var progress = eventStore.progress(PROCESSING_GROUP);

        assertThat(progress).contains(new ProcessingProgress(PROCESSING_GROUP, 3L, 3L, Optional.empty()));
        assertThat(progress).hasValueSatisfying(value -> {
            assertThat(value.remaining()).isZero();
            assertThat(value.replaying()).isFalse();
        });
    }

    private void storeEvents(int count) {
        for (int i = 0; i < count; i++) {
            eventStorage.store(serialize());
        }
    }

    private void storeEvent(long sequenceNumber) {
        var serializedEvent = serialize();
        eventStorage.store(new SerializedEvent(
            serializedEvent.messageId(),
            sequenceNumber,
            serializedEvent.subjectId(),
            serializedEvent.eventType(),
            serializedEvent.serializedPayload(),
            serializedEvent.serializedMetadata(),
            serializedEvent.createdAt()
        ));
    }

    private SerializedEvent serialize() {
        return eventSerializer.serialize(EventMessage.from(new TicketChanged(SUBJECT_ID), new Metadata(null, Instant.now(), null)));
    }

    record TicketChanged(@SubjectId String ticketId) implements Event {
    }

    private record Delivery(long sequenceNumber, String replay) {
    }
}
