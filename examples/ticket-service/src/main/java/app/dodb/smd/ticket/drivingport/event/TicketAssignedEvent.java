package app.dodb.smd.ticket.drivingport.event;

import app.dodb.smd.api.event.SubjectId;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record TicketAssignedEvent(@SubjectId UUID ticketId, String assignee) implements TicketEvent {

    public TicketAssignedEvent {
        requireNonNull(ticketId);
        requireNonNull(assignee);
    }
}
