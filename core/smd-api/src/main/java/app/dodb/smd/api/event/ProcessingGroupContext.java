package app.dodb.smd.api.event;

import app.dodb.smd.api.event.delivery.EventSource;

import static java.util.Objects.requireNonNull;

public record ProcessingGroupContext<E extends Event>(String processingGroup, EventSource source, EventMessage<E> eventMessage) {

    public ProcessingGroupContext {
        requireNonNull(processingGroup);
        requireNonNull(source);
        requireNonNull(eventMessage);
    }

    public ProcessingGroupContext<E> withEventMessage(EventMessage<E> eventMessage) {
        return new ProcessingGroupContext<>(processingGroup, source, eventMessage);
    }
}
