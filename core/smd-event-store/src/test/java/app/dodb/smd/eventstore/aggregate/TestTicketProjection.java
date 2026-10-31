package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ProcessingGroup("test-ticket-view")
public class TestTicketProjection {

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final List<Metadata> metadata = new CopyOnWriteArrayList<>();

    @EventHandler
    public void handle(TestTicketOpenedEvent event, Metadata metadata) {
        record(event, metadata);
    }

    @EventHandler
    public void handle(TestTicketAssignedEvent event, Metadata metadata) {
        record(event, metadata);
    }

    public List<Object> events() {
        return List.copyOf(events);
    }

    public List<Metadata> metadata() {
        return List.copyOf(metadata);
    }

    private void record(Object event, Metadata eventMetadata) {
        events.add(event);
        metadata.add(eventMetadata);
    }
}
