package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig.ProcessingConfig;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.store.TokenState;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import app.dodb.smd.test.EventChannelListenerStub;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.FAILED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SequencedEvent;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SequencedEventProcessingIntegrationTest {

    private static final String TYPED_SEQUENCE_PROCESSING_GROUP = "typed-sequence-processing-group";
    private static final int BATCH_SIZE = 10;
    private static final String SUBJECT = "subjectId";
    private static final String SEQUENCE_NUMBER = "sequenceNumber";

    @Test
    void subjectlessEvents_shareGlobalFailureAndAbandonmentState() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "global-sequence-processing-group";
            var handled = new CopyOnWriteArrayList<String>();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new SubjectlessTestEvent()),
                new SequencedEvent<>(2L, new SubjectlessTestEvent()),
                new SequencedEvent<>(3L, new TestEventWithSubjectId("subjectId-1"))
            );

            try (var channel = fixture.createChannel(ProcessingConfig.withoutDefaults()
                .maxRetries(0)
                .batchSize(BATCH_SIZE)
                .retryBackoffStrategy(fixed(Duration.ZERO))
                .gapTimeout(ofSeconds(1))
                .build())) {
                channel.subscribe(new EventChannelListenerStub(processingGroup, eventMessage -> {
                    var sequenceNumber = eventMessage.metadata().properties().get(SEQUENCE_NUMBER);
                    if ("1".equals(sequenceNumber)) {
                        throw new IllegalStateException("global sequence is stuck");
                    }
                    handled.add(sequenceNumber);
                }));

                await().untilAsserted(() -> {
                    assertThat(handled).containsExactly("3");
                    assertThat(fixture.globalEventSequenceState(processingGroup)
                        .map(EventSequenceState::status)).contains(ABANDONED);
                    assertThat(fixture.globalEventSequenceState(processingGroup)
                        .flatMap(EventSequenceState::lastProcessedSequenceNumber)).contains(2L);
                    assertThat(fixture.eventSequenceState(processingGroup, "subjectId-1")
                        .flatMap(EventSequenceState::lastProcessedSequenceNumber)).contains(3L);
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(3L);
                });
            }
        }
    }

    @Test
    void abandonedSubject_doesNotInvokeLaterEventsWithSameSubject() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-sequence-failure-processing-group";
            var invoked = new CopyOnWriteArrayList<String>();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId("subjectId-1")),
                new SequencedEvent<>(2L, new TestEventWithSubjectId("subjectId-2")),
                new SequencedEvent<>(3L, new DifferentTestEvent("subjectId-1"))
            );

            try (var channel = fixture.createChannel()) {
                channel.subscribe(new EventChannelListenerStub(processingGroup, eventMessage -> {
                    var metadata = eventMessage.metadata().properties();
                    invoked.add(metadata.get(SUBJECT) + ":" + metadata.get(SEQUENCE_NUMBER));
                    if ("subjectId-1".equals(metadata.get(SUBJECT))) {
                        throw new IllegalStateException("subjectId 1 is stuck");
                    }
                }));

                await().untilAsserted(() -> {
                    assertThat(invoked).containsExactly("subjectId-1:1", "subjectId-1:1", "subjectId-2:2");
                    assertThat(fixture.eventSequenceState(processingGroup, "subjectId-1")
                        .map(EventSequenceState::status)).contains(ABANDONED);
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(3L);
                });
            }
        }
    }

    @Test
    void failedSubject_doesNotBlockOtherSubjects() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-sequence-backoff-processing-group";
            var handled = new CopyOnWriteArrayList<String>();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId("subjectId-1")),
                new SequencedEvent<>(2L, new TestEventWithSubjectId("subjectId-2")),
                new SequencedEvent<>(3L, new TestEventWithSubjectId("subjectId-1"))
            );

            try (var channel = fixture.createChannel(ProcessingConfig.withoutDefaults()
                .maxRetries(5)
                .batchSize(BATCH_SIZE)
                .retryBackoffStrategy(fixed(Duration.ofDays(1)))
                .gapTimeout(ofSeconds(1))
                .build())) {
                channel.subscribe(new EventChannelListenerStub(processingGroup, eventMessage -> {
                    var metadata = eventMessage.metadata().properties();
                    if ("subjectId-1".equals(metadata.get(SUBJECT))) {
                        throw new IllegalStateException("subjectId 1 is in backoff");
                    }
                    handled.add(metadata.get(SUBJECT) + ":" + metadata.get(SEQUENCE_NUMBER));
                }));

                await().untilAsserted(() -> {
                    assertThat(handled).containsExactly("subjectId-2:2");
                    assertThat(fixture.eventSequenceState(processingGroup, "subjectId-1")
                        .map(EventSequenceState::status)).contains(FAILED);
                    assertThat(fixture.eventSequenceState(processingGroup, "subjectId-1")
                        .map(EventSequenceState::errorCount)).contains(1);
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).isEmpty();
                });
            }
        }
    }

    @Test
    void sequencedEvents_processAllEventsInSequence() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var processingGroup = "manual-sequence-processing-group";
            var handled = new CopyOnWriteArrayList<String>();
            fixture.storeEvents(
                new SequencedEvent<>(1L, new TestEventWithSubjectId("subjectId-1")),
                new SequencedEvent<>(2L, new TestEventWithSubjectId("subjectId-2")),
                new SequencedEvent<>(3L, new TestEventWithSubjectId("subjectId-1"))
            );

            try (var channel = fixture.createChannel()) {
                channel.subscribe(new EventChannelListenerStub(processingGroup, eventMessage -> {
                    var metadata = eventMessage.metadata().properties();
                    handled.add(metadata.get(SUBJECT) + ":" + metadata.get(SEQUENCE_NUMBER));
                }));

                await().untilAsserted(() -> {
                    assertThat(handled).containsExactly("subjectId-1:1", "subjectId-2:2", "subjectId-1:3");
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(3L);
                });
            }
        }
    }

    @Test
    void autoConfiguration_wiresSubjectSequencingWithoutCustomBinding() {
        try (var fixture = eventStoreTestFixture()
            .configuration(TypedEventSequenceConfiguration.class)
            .start()) {
            var eventBus = fixture.bean(EventBus.class);
            var handler = fixture.bean(TypedSequenceEventHandler.class);

            eventBus.publish(new TestEventWithSubjectId("subjectId-1"));
            eventBus.publish(new TestEventWithSubjectId("subjectId-2"));
            eventBus.publish(new TestEventWithSubjectId("subjectId-1"));

            await().untilAsserted(() -> {
                assertThat(handler.handledSubjects()).containsExactly("subjectId-2");
                assertThat(handler.invokedSequenceNumbers()).contains("1", "2").doesNotContain("3");
                assertThat(fixture.eventSequenceState(TYPED_SEQUENCE_PROCESSING_GROUP, "subjectId-1")
                    .map(EventSequenceState::status)).contains(ABANDONED);
                assertThat(fixture.tokenState(TYPED_SEQUENCE_PROCESSING_GROUP)
                    .flatMap(TokenState::lastProcessedSequenceNumber)).contains(3L);
            });
        }
    }

    @Configuration
    @EnableSMD(packages = "app.dodb.smd.spring.eventstore")
    static class TypedEventSequenceConfiguration {

        @Bean
        TypedSequenceEventHandler typedSequenceEventHandler() {
            return new TypedSequenceEventHandler();
        }

        @Bean
        ProcessingGroupsConfigurer typedSequenceProcessingGroupsConfigurer(EventStoreChannel eventStoreChannel) {
            return spec -> spec.processingGroup(TYPED_SEQUENCE_PROCESSING_GROUP)
                .channel(eventStoreChannel);
        }
    }

    @ProcessingGroup(TYPED_SEQUENCE_PROCESSING_GROUP)
    public static class TypedSequenceEventHandler {

        private final List<String> handledSubjects = new CopyOnWriteArrayList<>();
        private final List<String> invokedSequenceNumbers = new CopyOnWriteArrayList<>();

        @EventHandler
        public void on(TestEventWithSubjectId event, Metadata metadata) {
            var subject = metadata.properties().get(SUBJECT);
            invokedSequenceNumbers.add(metadata.properties().get(SEQUENCE_NUMBER));
            if ("subjectId-1".equals(subject)) {
                throw new IllegalStateException("subjectId 1 is stuck");
            }
            handledSubjects.add(subject);
        }

        List<String> handledSubjects() {
            return List.copyOf(handledSubjects);
        }

        List<String> invokedSequenceNumbers() {
            return List.copyOf(invokedSequenceNumbers);
        }
    }

    private record DifferentTestEvent(@SubjectId String subjectId) implements Event {
    }

    private record SubjectlessTestEvent() implements Event {
    }
}
