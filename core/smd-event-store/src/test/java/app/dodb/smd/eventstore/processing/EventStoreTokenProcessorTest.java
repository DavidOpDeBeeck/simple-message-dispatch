package app.dodb.smd.eventstore.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import app.dodb.smd.test.NoOpTransactionProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.EventStoreMetadata.GLOBAL_POSITION;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofHours;
import static org.assertj.core.api.Assertions.assertThat;

class EventStoreTokenProcessorTest {

    private static final String SUBJECT_TYPE = "ticket";

    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final InMemoryEventSequenceStore eventSequenceStore = new InMemoryEventSequenceStore();
    private final EventSerializer eventSerializer = new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build(), new ClassNameEventTypeResolver(), List.of());

    @Test
    void poll_backoffEndingBetweenEventsOfWaitingSequence_skipsLaterEventsOfThatSequence() {
        var backoffChecks = new AtomicInteger();
        var processor = tokenProcessor(_ -> backoffChecks.getAndIncrement() == 0 ? ofHours(1) : ZERO);
        var subscriber = new FailingTicketSubscriber("ticket-1");
        append(new TicketEvent("ticket-1"));
        append(new TicketEvent("ticket-2"));
        append(new TicketEvent("ticket-1"));
        processor.poll(subscriber);

        processor.poll(subscriber);

        assertThat(subscriber.invokedPositions()).containsExactly("1", "2");
        assertThat(eventSequenceStore.eventSequenceState(subscriber.name(), new EventSequenceId.OfSubject(new Subject(SUBJECT_TYPE, "ticket-1"))))
            .hasValueSatisfying(state -> assertThat(state.errorCount()).isEqualTo(1));
    }

    private EventStoreTokenProcessor tokenProcessor(RetryBackoffStrategy retryBackoffStrategy) {
        return new EventStoreTokenProcessor(EventStoreConfig.withDefaults()
            .transactionProvider(new NoOpTransactionProvider())
            .eventStorage(eventStorage)
            .eventSerializer(eventSerializer)
            .tokenStore(new InMemoryTokenStore())
            .eventSequenceStore(eventSequenceStore)
            .processingConfig(ProcessingConfig.withDefaults().retryBackoffStrategy(retryBackoffStrategy).build())
            .build());
    }

    private void append(Event event) {
        eventStorage.append(NewEvent.from(eventSerializer.serialize(EventMessage.from(event, Metadata.at(Instant.parse("2026-11-11T10:00:00Z"))))));
    }

    private record TicketEvent(@SubjectId(SUBJECT_TYPE) String ticketId) implements Event {
    }

    private static final class FailingTicketSubscriber implements EventSubscriber {

        private final String failingTicketId;
        private final List<String> invokedPositions = new ArrayList<>();

        private FailingTicketSubscriber(String failingTicketId) {
            this.failingTicketId = failingTicketId;
        }

        @Override
        public String name() {
            return "ticket-projection";
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            invokedPositions.add(eventMessage.metadata().transientProperties().get(GLOBAL_POSITION));
            if (eventMessage.payload() instanceof TicketEvent(var ticketId) && ticketId.equals(failingTicketId)) {
                throw new IllegalStateException("Ticket " + ticketId + " cannot be projected");
            }
        }

        List<String> invokedPositions() {
            return invokedPositions;
        }
    }
}
