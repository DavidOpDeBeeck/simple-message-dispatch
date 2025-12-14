package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventChannelListener {

    <E extends Event> void on(EventMessage<E> eventMessage);
}
