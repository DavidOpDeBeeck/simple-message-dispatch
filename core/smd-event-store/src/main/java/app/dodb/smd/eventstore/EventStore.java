package app.dodb.smd.eventstore;

import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;

import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class EventStore implements EventMedium, AutoCloseable {

    public static final EventDelivery DELIVERY = new EventDelivery("event-store");

    private final EventStoreConfig config;
    private final EventStoreSink sink;
    private final EventStoreSource source;

    public EventStore(EventStoreConfig config) {
        this.config = requireNonNull(config);
        this.sink = new EventStoreSink(config);
        this.source = new EventStoreSource(config);
    }

    @Override
    public EventSink inlet() {
        return sink;
    }

    @Override
    public EventSource outlet() {
        return source;
    }

    public CommandHandlerLocator aggregateCommandHandlers(Collection<Class<?>> aggregateClasses, EventPublisher eventPublisher) {
        return new AggregateCommandHandlerLocator(List.copyOf(aggregateClasses), config, eventPublisher);
    }

    @Override
    public void close() {
        source.close();
    }
}
