package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.serialization.EventSerializer;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.eventstore.EventStoreMetadata.withSubjectVersion;
import static java.util.Objects.requireNonNull;

public class AggregateRepository<A> {

    private final AggregateModel<A> model;
    private final TransactionProvider transactionProvider;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;
    private final EventPublisher eventPublisher;
    private final MetadataFactory metadataFactory;

    AggregateRepository(AggregateModel<A> model, EventStore eventStore, EventPublisher eventPublisher, MetadataFactory metadataFactory) {
        this.model = requireNonNull(model);
        requireNonNull(eventStore);
        this.transactionProvider = eventStore.config().getTransactionProvider();
        this.eventStorage = eventStore.config().getEventStorage();
        this.eventSerializer = eventStore.config().getEventSerializer();
        this.eventPublisher = requireNonNull(eventPublisher);
        this.metadataFactory = requireNonNull(metadataFactory);
    }

    public <E extends Event> A create(Function<AggregateEvents<E>, A> creating) {
        return transactionProvider.doInTransaction(() -> {
            var events = new AppliedAggregateEvents.Creating<A, E>(model, metadataFactory);
            var aggregate = creating.apply(events);

            var eventMessages = events.complete(aggregate);
            if (eventMessages.isEmpty()) {
                throw new IllegalStateException("""
                    Invalid aggregate creation: no events were applied, so there is no stream to store.

                        Aggregate:
                        %s
                    """.formatted(logClass(model.aggregateClass())));
            }

            var aggregateId = model.aggregateId(aggregate).orElseThrow(() -> new IllegalStateException("""
                Invalid aggregate creation: @AggregateId is null after applying the creation events. An @EventSourcingHandler must assign it.

                    Aggregate:
                    %s
                """.formatted(logClass(model.aggregateClass()))));
            publish(subject(aggregateId), 0, eventMessages);
            return aggregate;
        });
    }

    public <R, E extends Event> R update(Object aggregateId, BiFunction<A, AggregateEvents<E>, R> updating) {
        var subject = subject(aggregateId);
        return transactionProvider.doInTransaction(() -> {
            var history = load(subject);
            if (history.isEmpty()) {
                throw new AggregateNotFoundException(subject.type(), subject.id());
            }

            var aggregate = model.replay(history);
            var events = new AppliedAggregateEvents.Updating<A, E>(model, metadataFactory, aggregate);
            var result = updating.apply(aggregate, events);
            publish(subject, history.size(), events.complete());
            return result;
        });
    }

    AggregateModel<A> model() {
        return model;
    }

    private Subject subject(Object aggregateId) {
        return new Subject(model.aggregateType(), aggregateId.toString());
    }

    private List<EventMessage<?>> load(Subject subject) {
        return eventStorage.loadSubjectStream(subject).stream()
            .<EventMessage<?>>map(storedEvent -> eventSerializer.deserialize(storedEvent.serializedEvent()))
            .toList();
    }

    void publish(Subject subject, long loadedVersion, List<EventMessage<?>> eventMessages) {
        eventMessages.forEach(eventMessage -> validateSubject(subject, eventMessage));

        var version = loadedVersion;
        for (var eventMessage : eventMessages) {
            version++;
            eventPublisher.publish(new EventMessage<>(
                eventMessage.messageId(),
                Optional.of(subject),
                eventMessage.payload(),
                withSubjectVersion(eventMessage.metadata(), version)
            ));
        }
    }

    private void validateSubject(Subject subject, EventMessage<?> eventMessage) {
        if (eventMessage.subject().filter(declared -> !declared.equals(subject)).isPresent()) {
            throw new IllegalArgumentException("""
                Invalid aggregate event: a declared @SubjectId must resolve to the aggregate's type and ID.
                Aggregate events need no @SubjectId; their subject is the aggregate.
                
                    Aggregate:
                    %s
                
                    Event:
                    %s
                
                    Subject:
                    %s
                """.formatted(subject, logClass(eventMessage.payload().getClass()), eventMessage.subject().orElseThrow()));
        }
    }
}
