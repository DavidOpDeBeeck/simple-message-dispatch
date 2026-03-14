package app.dodb.smd.spring.event.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@ProcessingGroup
public class FailableTestEventHandler {

    private final AtomicInteger failuresRemaining = new AtomicInteger(0);
    private final List<Event> handledEvents = new CopyOnWriteArrayList<>();

    public void failNextNAttempts(int n) {
        failuresRemaining.set(n);
    }

    @EventHandler
    public void on(AnotherTestEvent event) {
        if (failuresRemaining.getAndDecrement() > 0) {
            throw new RuntimeException("Simulated failure");
        }
        handledEvents.add(event);
    }

    public List<Event> getHandledEvents() {
        return List.copyOf(handledEvents);
    }
}
