package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventMedium {

    EventSink inlet();

    EventSource outlet();

    default <E extends Event> void send(EventMessage<E> eventMessage) {
        inlet().send(eventMessage);
    }

    default EventSubscription subscribe(EventSubscriber subscriber) {
        return outlet().subscribe(subscriber);
    }

    default EventMedium selecting(EventSelector selector) {
        return new SelectingEventMedium(this, selector);
    }
}
