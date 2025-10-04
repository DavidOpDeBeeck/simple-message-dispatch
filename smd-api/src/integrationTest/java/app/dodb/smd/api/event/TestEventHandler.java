package app.dodb.smd.api.event;

import java.util.ArrayList;
import java.util.List;

public class TestEventHandler {

    static List<Event> handledEvents = new ArrayList<>();

    @EventHandler
    @ProcessingGroup("1")
    public void on(TestEvent event) {
        handledEvents.add(event);
    }

    @EventHandler
    @ProcessingGroup("2")
    public void on(AnotherTestEvent event) {
        handledEvents.add(event);
    }
}
