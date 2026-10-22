package app.dodb.smd.jms;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;

import static java.util.Objects.requireNonNull;

public class JmsEventMedium implements EventMedium, AutoCloseable {

    public static final EventDelivery DELIVERY = new EventDelivery("jms");

    private final JmsEventSink inlet;
    private final JmsEventSource outlet;

    public JmsEventMedium(JmsConfig config, JmsDestination destination) {
        requireNonNull(config);
        requireNonNull(destination);
        this.inlet = new JmsEventSink(config, destination);
        this.outlet = new JmsEventSource(config, destination);
    }

    @Override
    public JmsEventSink inlet() {
        return inlet;
    }

    @Override
    public JmsEventSource outlet() {
        return outlet;
    }

    @Override
    public void close() {
        outlet.close();
        inlet.close();
    }
}
