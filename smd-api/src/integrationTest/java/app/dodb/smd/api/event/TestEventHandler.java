package app.dodb.smd.api.event;

import java.util.ArrayList;
import java.util.List;

public class TestEventHandler {

    static List<Event> handledEvents = new ArrayList<>();

    @EventHandler
    public void on(TestEvent event) {
        handledEvents.add(event);
    }
}
