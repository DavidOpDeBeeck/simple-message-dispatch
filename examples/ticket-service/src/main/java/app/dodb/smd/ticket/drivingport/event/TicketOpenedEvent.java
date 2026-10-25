package app.dodb.smd.ticket.drivingport.event;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record TicketOpenedEvent(UUID ticketId, String title, String description) implements TicketEvent {

    public TicketOpenedEvent {
        requireNonNull(ticketId);
        requireNonNull(title);
        requireNonNull(description);
    }
}
