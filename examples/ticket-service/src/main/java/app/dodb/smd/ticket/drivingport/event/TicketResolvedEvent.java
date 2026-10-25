package app.dodb.smd.ticket.drivingport.event;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record TicketResolvedEvent(UUID ticketId, String resolution) implements TicketEvent {

    public TicketResolvedEvent {
        requireNonNull(ticketId);
        requireNonNull(resolution);
    }
}
