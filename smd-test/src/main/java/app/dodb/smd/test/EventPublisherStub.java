package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;

import java.util.ArrayList;
import java.util.List;

public class EventPublisherStub implements EventPublisher {

    private final List<Event> events = new ArrayList<>();

    @Override
    public <E extends Event> void publish(E event) {
        events.add(event);
    }

    @Override
    public <E extends Event> void publish(EventMessage<E> eventMessage) {
        events.add(eventMessage.getPayload());
    }

    public List<Event> getEvents() {
        return events;
    }

    public void reset() {
        events.clear();
    }
}
