package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.ArrayList;
import java.util.List;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

abstract sealed class AppliedAggregateEvents<A, E extends Event> implements AggregateEvents<E>
    permits AppliedAggregateEvents.Creating, AppliedAggregateEvents.Updating {

    final AggregateModel<A> model;
    private final MetadataFactory metadataFactory;
    private final List<EventMessage<?>> eventMessages = new ArrayList<>();
    private boolean completed;

    private AppliedAggregateEvents(AggregateModel<A> model, MetadataFactory metadataFactory) {
        this.model = requireNonNull(model);
        this.metadataFactory = requireNonNull(metadataFactory);
    }

    @Override
    public final void apply(E event) {
        if (completed) {
            throw new IllegalStateException("""
                Invalid aggregate event: events can only be applied while the command handler runs.
                
                    Aggregate:
                    %s
                
                    Event:
                    %s
                """.formatted(logClass(model.aggregateClass()), logClass(event.getClass())));
        }

        // Built now, in the handler's metadata scope, so event sourcing handlers see the message that is later published and stored.
        var eventMessage = EventMessage.from(event, metadataFactory.createScope().metadata());
        applied(eventMessage);
        eventMessages.add(eventMessage);
    }

    abstract void applied(EventMessage<?> eventMessage);

    List<EventMessage<?>> complete() {
        completed = true;
        return List.copyOf(eventMessages);
    }

    static final class Creating<A, E extends Event> extends AppliedAggregateEvents<A, E> {

        Creating(AggregateModel<A> model, MetadataFactory metadataFactory) {
            super(model, metadataFactory);
        }

        @Override
        void applied(EventMessage<?> eventMessage) {
            // A creating constructor has no instance yet: its events are applied once the constructor returns it.
        }

        List<EventMessage<?>> complete(A aggregate) {
            var eventMessages = complete();
            eventMessages.forEach(eventMessage -> model.apply(aggregate, eventMessage));
            return eventMessages;
        }
    }

    static final class Updating<A, E extends Event> extends AppliedAggregateEvents<A, E> {

        private final A aggregate;

        Updating(AggregateModel<A> model, MetadataFactory metadataFactory, A aggregate) {
            super(model, metadataFactory);
            this.aggregate = requireNonNull(aggregate);
        }

        @Override
        void applied(EventMessage<?> eventMessage) {
            model.apply(aggregate, eventMessage);
        }
    }
}
