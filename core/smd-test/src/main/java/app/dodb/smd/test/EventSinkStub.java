package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventSinkStub implements EventSink {

    private static final EventDelivery TESTING = new EventDelivery("testing");

    private final List<EventMessage<?>> eventMessages = new CopyOnWriteArrayList<>();

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        eventMessages.add(eventMessage);
    }

    @Override
    public EventDelivery delivery() {
        return TESTING;
    }

    public List<EventMessage<?>> getEventMessages() {
        return List.copyOf(eventMessages);
    }

    public void reset() {
        eventMessages.clear();
    }
}
