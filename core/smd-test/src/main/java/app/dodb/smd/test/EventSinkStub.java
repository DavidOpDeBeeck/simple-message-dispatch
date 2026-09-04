package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventSinkStub implements EventSink {

    private final List<EventMessage<?>> eventMessages = new CopyOnWriteArrayList<>();

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        eventMessages.add(eventMessage);
    }

    public List<EventMessage<?>> getEventMessages() {
        return List.copyOf(eventMessages);
    }

    public void reset() {
        eventMessages.clear();
    }
}
