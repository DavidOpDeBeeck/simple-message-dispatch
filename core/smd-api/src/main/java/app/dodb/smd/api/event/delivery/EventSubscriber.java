package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventSubscriber {

    String name();

    <E extends Event> void on(EventMessage<E> eventMessage);
}
