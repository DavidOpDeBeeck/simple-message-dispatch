package app.dodb.smd.ticket.drivingport.event;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record TicketAssignedEvent(UUID ticketId, String assignee) implements TicketEvent {

    public TicketAssignedEvent {
        requireNonNull(ticketId);
        requireNonNull(assignee);
    }
}
