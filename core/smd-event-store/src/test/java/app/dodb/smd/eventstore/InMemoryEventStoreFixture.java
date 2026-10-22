package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMillis;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;

final class InMemoryEventStoreFixture implements AutoCloseable {

    final InMemoryEventStorage storage = new InMemoryEventStorage();
    final InMemoryTokenStore tokens = new InMemoryTokenStore();
    final InMemoryEventSubjectSequenceStore sequences = new InMemoryEventSubjectSequenceStore();
    final DeferringTransactionProvider transactions = new DeferringTransactionProvider();
    final JacksonEventSerializer serializer = new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
    final EventStore eventStore;

    InMemoryEventStoreFixture(EventSinkInterceptor... sinkInterceptors) {
        this(fixed(ZERO), sinkInterceptors);
    }

    InMemoryEventStoreFixture(RetryBackoffStrategy retryBackoffStrategy, EventSinkInterceptor... sinkInterceptors) {
        this.eventStore = new EventStore(EventStoreConfig.withoutDefaults()
            .transactionProvider(transactions)
            .eventStorage(storage)
            .eventSerializer(serializer)
            .tokenStore(tokens)
            .eventSequenceStore(sequences)
            .schedulingConfig(SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(newScheduledThreadPool(2))
                .initialDelay(ZERO)
                .pollingDelay(ofMillis(10))
                .build())
            .processingConfig(ProcessingConfig.withoutDefaults()
                .maxRetries(1)
                .batchSize(10)
                .retryBackoffStrategy(retryBackoffStrategy)
                .gapTimeout(ofHours(1))
                .build())
            .sinkInterceptors(sinkInterceptors)
            .build());
    }

    SerializedEvent serialized(EventMessage<?> eventMessage, long sequenceNumber) {
        var serialized = serializer.serialize(eventMessage);
        return new SerializedEvent(serialized.messageId(), sequenceNumber, serialized.subjectId(), serialized.eventType(),
            serialized.serializedPayload(), serialized.serializedMetadata(), serialized.createdAt());
    }

    @Override
    public void close() {
        eventStore.close();
    }

    static final class RecordingSink implements EventSink {

        private final List<EventMessage<?>> received = new CopyOnWriteArrayList<>();
        private final Predicate<EventMessage<?>> fails;

        RecordingSink() {
            this(_ -> false);
        }

        RecordingSink(Predicate<EventMessage<?>> fails) {
            this.fails = requireNonNull(fails);
        }

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            if (fails.test(eventMessage)) {
                throw new IllegalStateException("Sink unavailable for " + eventMessage.messageId());
            }
            received.add(eventMessage);
        }

        @Override
        public EventDelivery delivery() {
            return new EventDelivery("recording");
        }

        List<EventMessage<?>> received() {
            return received;
        }

        List<String> changes() {
            return received.stream().map(message -> ((TicketEvent) message.payload()).change()).toList();
        }
    }

    static final class RecordingSubscriber implements EventSubscriber {

        private final String processingGroup;
        private final List<EventMessage<?>> received = new CopyOnWriteArrayList<>();
        private final Predicate<EventMessage<?>> fails;

        RecordingSubscriber(String processingGroup) {
            this(processingGroup, _ -> false);
        }

        RecordingSubscriber(String processingGroup, Predicate<EventMessage<?>> fails) {
            this.processingGroup = requireNonNull(processingGroup);
            this.fails = requireNonNull(fails);
        }

        @Override
        public String processingGroup() {
            return processingGroup;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            if (fails.test(eventMessage)) {
                throw new IllegalStateException("Handler failed for " + eventMessage.messageId());
            }
            received.add(eventMessage);
        }

        List<EventMessage<?>> received() {
            return received;
        }

        List<String> changes() {
            return received.stream().map(message -> ((TicketEvent) message.payload()).change()).toList();
        }
    }
}
