package app.dodb.smd.api.event.delivery;

import static java.util.Objects.requireNonNull;

public record EventRoute(String name, EventSource source, EventSink sink) {

    public EventRoute {
        requireNonNull(name);
        requireNonNull(source);
        requireNonNull(sink);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Event route requires a non-blank name; got '" + name + "'");
        }
    }
}
