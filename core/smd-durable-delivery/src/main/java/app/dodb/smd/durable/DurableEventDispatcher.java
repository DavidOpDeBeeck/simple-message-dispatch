package app.dodb.smd.durable;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;

import static java.util.Objects.requireNonNull;

public class DurableEventDispatcher implements EventMedium {

    public static final EventDelivery DELIVERY = new EventDelivery("durable");

    private final DurableEventDispatcherSink sink;
    private final DurableEventDispatcherSource source;

    DurableEventDispatcher(String name, DurableEventDispatcherConfig config) {
        requireNonNull(config);
        this.sink = new DurableEventDispatcherSink(name, config);
        this.source = new DurableEventDispatcherSource(name, config);
    }

    @Override
    public EventSink inlet() {
        return sink;
    }

    @Override
    public EventSource outlet() {
        return source;
    }
}
