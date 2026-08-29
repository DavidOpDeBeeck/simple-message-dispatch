package app.dodb.smd.ticket.drivenport;

import app.dodb.smd.ticket.domain.Ticket;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository {

    Optional<Ticket> find(UUID ticketId);

    void save(Ticket ticket);
}
