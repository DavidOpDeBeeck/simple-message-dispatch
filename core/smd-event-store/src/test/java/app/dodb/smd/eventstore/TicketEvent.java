package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.metadata.Metadata;

import java.time.Instant;
import java.util.Map;

record TicketEvent(@SubjectId String ticketId, String change) implements Event {

    static EventMessage<TicketEvent> ticketEvent(String ticketId, String change) {
        return EventMessage.from(new TicketEvent(ticketId, change), new Metadata(null, Instant.parse("2026-10-22T10:15:30Z"), null, Map.of("tenant", "acme")));
    }
}
