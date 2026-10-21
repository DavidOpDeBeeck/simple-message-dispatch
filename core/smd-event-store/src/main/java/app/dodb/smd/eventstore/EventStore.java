package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.eventstore.storage.EventLog;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static app.dodb.smd.eventstore.EventLogConsumption.FORWARDING;
import static java.util.Objects.requireNonNull;

public class EventStore implements EventMedium, AutoCloseable {

    public static final EventDelivery DELIVERY = new EventDelivery("event-store");

    private final EventStoreConfig config;
    private final EventLogPolling polling;
    private final EventStoreSink sink;
    private final EventStoreSource source;
    private final Set<String> outboxNames = ConcurrentHashMap.newKeySet();

    public EventStore(EventStoreConfig config) {
        this.config = requireNonNull(config);
        this.polling = new EventLogPolling(config.getSchedulingConfig());
        this.sink = new EventStoreSink(config, EventLog.EVENTS);
        this.source = new EventStoreSource(config, polling);
    }

    @Override
    public EventSink inlet() {
        return sink;
    }

    @Override
    public EventSource outlet() {
        return source;
    }

    public EventSink outbox(String name, EventSink sink) {
        requireNonNull(sink);
        var log = EventLog.outbox(name);
        if (!outboxNames.add(name)) {
            throw new IllegalArgumentException("Event store already has an outbox named '" + name + "'");
        }

        polling.schedule(new EventStoreTokenProcessor(config, log, FORWARDING), new EventStoreOutboxForwarder(name, sink, config.getSinkInterceptors()));
        return new EventStoreSink(config, log);
    }

    public EventSource inbox(EventSource source) {
        return new EventStoreInbox(config, polling, source);
    }

    public EventMedium durable(String name, EventMedium medium) {
        requireNonNull(medium);
        return new DurableEventMedium(outbox(name, medium.inlet()), inbox(medium.outlet()));
    }

    @Override
    public void close() {
        polling.close();
    }

    private record DurableEventMedium(EventSink inlet, EventSource outlet) implements EventMedium {

        DurableEventMedium {
            requireNonNull(inlet);
            requireNonNull(outlet);
        }
    }
}
