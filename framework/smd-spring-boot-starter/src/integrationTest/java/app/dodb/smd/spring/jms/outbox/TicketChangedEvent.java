package app.dodb.smd.spring.jms.outbox;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record TicketChangedEvent(@SubjectId String ticketId, String change) implements Event {
}
