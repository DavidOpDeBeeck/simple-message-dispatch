package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@ProcessingGroup
public class TestEventHandler {

    private final List<Event> handledEvents = new CopyOnWriteArrayList<>();
    private final CountDownLatch eventHandled = new CountDownLatch(1);

    @EventHandler
    public void on(TestEvent event) {
        handledEvents.add(event);
        eventHandled.countDown();
    }

    public void awaitEvent() throws InterruptedException {
        assertThat(eventHandled.await(5, SECONDS)).as("event handled").isTrue();
    }

    public List<Event> getHandledEvents() {
        return List.copyOf(handledEvents);
    }
}
