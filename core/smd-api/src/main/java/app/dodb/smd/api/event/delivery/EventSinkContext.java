package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import static java.util.Objects.requireNonNull;

public record EventSinkContext<E extends Event>(EventSink sink, EventMessage<E> eventMessage) {

    public EventSinkContext {
        requireNonNull(sink);
        requireNonNull(eventMessage);
    }

    public EventSinkContext<E> withEventMessage(EventMessage<E> eventMessage) {
        return new EventSinkContext<>(sink, eventMessage);
    }
}
