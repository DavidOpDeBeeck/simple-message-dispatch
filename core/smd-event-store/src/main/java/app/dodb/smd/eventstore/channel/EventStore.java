package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.event.channel.EventChannelListener;

import static java.util.Objects.requireNonNull;

public class EventStore implements EventChannel, AutoCloseable {

    private final EventStoreSink sink;
    private final EventStoreSource source;

    public EventStore(EventStoreConfig config) {
        requireNonNull(config);
        this.sink = new EventStoreSink(config);
        this.source = new EventStoreSource(config);
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        sink.send(eventMessage);
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        source.subscribe(listener);
    }

    @Override
    public void close() {
        source.close();
    }
}
