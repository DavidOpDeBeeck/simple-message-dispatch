package app.dodb.smd.api.event.delivery;

import static java.util.Objects.requireNonNull;

public record EventDelivery(String name) {

    public static final EventDelivery SYNC = new EventDelivery("sync");
    public static final EventDelivery ASYNC_AWAIT = new EventDelivery("async-await");
    public static final EventDelivery ASYNC_FIRE_AND_FORGET = new EventDelivery("async-fire-and-forget");

    public EventDelivery {
        requireNonNull(name);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Event delivery requires a non-blank name; got '" + name + "'");
        }
    }
}
