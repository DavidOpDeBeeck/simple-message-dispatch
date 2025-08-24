package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;

import java.util.ArrayList;
import java.util.List;

public class TestEventHandler {

    private final List<Event> handledEvents = new ArrayList<>();

    @EventHandler
    public void on(TestEvent event) {
        handledEvents.add(event);
    }

    public List<Event> getHandledEvents() {
        return handledEvents;
    }
}
