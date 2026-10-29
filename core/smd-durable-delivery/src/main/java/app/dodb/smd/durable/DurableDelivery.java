package app.dodb.smd.durable;

import static java.util.Objects.requireNonNull;

public class DurableDelivery {

    private final DurableEventDispatcherConfig config;

    public DurableDelivery(DurableEventDispatcherConfig config) {
        this.config = requireNonNull(config);
    }

    public DurableEventDispatcher dispatcher(String name) {
        return new DurableEventDispatcher(name, config);
    }
}
