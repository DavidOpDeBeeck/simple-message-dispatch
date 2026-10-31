package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.eventstore.processing.EventStoreSource;

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

    public EventStoreConfig config() {
        return config;
    }

    @Override
    public void close() {
        source.close();
    }
}
