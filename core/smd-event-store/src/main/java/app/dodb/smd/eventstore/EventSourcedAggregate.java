package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

final class EventSourcedAggregate<A> implements AggregateEvents<Event> {

    private final AggregateModel<A> model;
    private final Optional<String> loadedAggregateId;
    private final long loadedVersion;
    private final List<Event> appliedEvents = new ArrayList<>();
    // Null while a creation constructor runs: its events are applied once the constructor returns the instance.
    private A instance;
    private boolean handled;

    private EventSourcedAggregate(AggregateModel<A> model, A instance, Optional<String> loadedAggregateId, long loadedVersion) {
        this.model = requireNonNull(model);
        this.instance = instance;
        this.loadedAggregateId = requireNonNull(loadedAggregateId);
        this.loadedVersion = loadedVersion;
    }

    static <A> EventSourcedAggregate<A> creating(AggregateModel<A> model) {
        return new EventSourcedAggregate<>(model, null, Optional.empty(), 0L);
    }

    static <A> EventSourcedAggregate<A> replay(AggregateModel<A> model, String aggregateId, List<Event> history) {
        var instance = model.newInstance();
        history.forEach(event -> model.applyEvent(instance, event));
        return new EventSourcedAggregate<>(model, instance, Optional.of(aggregateId), history.size());
    }

    @Override
    public void apply(Event event) {
        requireNonNull(event);
        if (handled) {
            throw new IllegalStateException("""
                Invalid aggregate event: events can only be applied while the command handler runs.

                    Aggregate:
                    %s

                    Event:
                    %s
                """.formatted(logClass(model.aggregateClass()), logClass(event.getClass())));
        }

        if (instance != null) {
            model.applyEvent(instance, event);
        }
        appliedEvents.add(event);
    }

    void created(A instance) {
        this.instance = requireNonNull(instance);
        appliedEvents.forEach(event -> model.applyEvent(instance, event));
    }

    A instance() {
        return instance;
    }

    Optional<String> loadedAggregateId() {
        return loadedAggregateId;
    }

    long loadedVersion() {
        return loadedVersion;
    }

    List<Event> completeHandling() {
        handled = true;
        return List.copyOf(appliedEvents);
    }
}
