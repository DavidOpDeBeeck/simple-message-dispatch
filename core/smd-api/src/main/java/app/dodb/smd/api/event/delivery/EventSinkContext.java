package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record EventSinkContext<E extends Event>(EventSink sink, EventMessage<E> eventMessage, Optional<EventRoute> route) {

    public EventSinkContext(EventSink sink, EventMessage<E> eventMessage) {
        this(sink, eventMessage, Optional.empty());
    }

    public EventSinkContext {
        requireNonNull(sink);
        requireNonNull(eventMessage);
        requireNonNull(route);
    }

    public EventSinkContext<E> withEventMessage(EventMessage<E> eventMessage) {
        return new EventSinkContext<>(sink, eventMessage, route);
    }
}
