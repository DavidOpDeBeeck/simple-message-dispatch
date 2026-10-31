package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.metadata.principal.Principal;

import java.time.Instant;
import java.util.UUID;

public record TestTicketStampedEvent(UUID ticketId, Instant openedAt, Principal openedBy) implements Event {
}
