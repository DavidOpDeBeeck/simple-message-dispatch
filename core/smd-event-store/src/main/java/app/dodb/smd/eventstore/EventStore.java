package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;

import static java.util.Objects.requireNonNull;

public class EventStore implements EventMedium, AutoCloseable {

    private final EventStoreSink sink;
    private final EventStoreSource source;

    public EventStore(EventStoreConfig config) {
        requireNonNull(config);
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

    @Override
    public void close() {
        source.close();
    }
}
