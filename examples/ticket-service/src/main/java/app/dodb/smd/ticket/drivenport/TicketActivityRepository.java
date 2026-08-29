package app.dodb.smd.ticket.drivenport;

import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;

import java.util.List;
import java.util.UUID;

public interface TicketActivityRepository {

    List<TicketActivityDTO> findAll(UUID ticketId);
}
