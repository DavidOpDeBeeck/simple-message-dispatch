package app.dodb.smd.ticket.drivingport.event;

import app.dodb.smd.api.event.SubjectId;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record TicketResolvedEvent(@SubjectId UUID ticketId, String resolution) implements TicketEvent {

    public TicketResolvedEvent {
        requireNonNull(ticketId);
        requireNonNull(resolution);
    }
}
