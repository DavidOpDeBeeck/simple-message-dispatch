package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventSink {

    <E extends Event> void send(EventMessage<E> eventMessage);

    default EventSink selecting(EventSelector selector) {
        return new SelectingEventSink(this, selector);
    }
}
