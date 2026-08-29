package app.dodb.smd.ticket.drivingport.query;

import app.dodb.smd.api.query.Query;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;

import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record GetTicketQuery(UUID ticketId) implements Query<Optional<TicketDTO>> {

    public GetTicketQuery {
        requireNonNull(ticketId);
    }
}
