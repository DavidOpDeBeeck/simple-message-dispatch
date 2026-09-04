package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import static java.util.Objects.requireNonNull;

public class SelectingEventSink implements EventSink {

    private final EventSink delegate;
    private final EventSelector selector;

    public SelectingEventSink(EventSink delegate, EventSelector selector) {
        this.delegate = requireNonNull(delegate);
        this.selector = requireNonNull(selector);
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        if (selector.selects(eventMessage)) {
            delegate.send(eventMessage);
        }
    }
}
