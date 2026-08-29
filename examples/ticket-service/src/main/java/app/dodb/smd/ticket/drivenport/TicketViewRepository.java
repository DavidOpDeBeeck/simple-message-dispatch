package app.dodb.smd.ticket.drivenport;

import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;

import java.util.Optional;
import java.util.UUID;

public interface TicketViewRepository {

    Optional<TicketDTO> find(UUID ticketId);
}
