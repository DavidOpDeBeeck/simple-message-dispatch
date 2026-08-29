package app.dodb.smd.ticket.drivingport.query;

import app.dodb.smd.api.query.Query;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record GetTicketActivityQuery(UUID ticketId) implements Query<List<TicketActivityDTO>> {

    public GetTicketActivityQuery {
        requireNonNull(ticketId);
    }
}
