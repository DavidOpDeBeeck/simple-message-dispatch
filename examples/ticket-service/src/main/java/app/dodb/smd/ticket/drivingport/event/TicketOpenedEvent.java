package app.dodb.smd.ticket.drivingport.event;

import app.dodb.smd.api.event.SubjectId;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record TicketOpenedEvent(@SubjectId UUID ticketId, String title, String description) implements TicketEvent {

    public TicketOpenedEvent {
        requireNonNull(ticketId);
        requireNonNull(title);
        requireNonNull(description);
    }
}
