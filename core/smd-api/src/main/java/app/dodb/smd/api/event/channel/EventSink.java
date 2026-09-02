package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventSink {

    <E extends Event> void send(EventMessage<E> eventMessage);
}
