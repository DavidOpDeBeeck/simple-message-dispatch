package app.dodb.smd.ticket.usecase.stubs;

import app.dodb.smd.ticket.domain.Ticket;
import app.dodb.smd.ticket.drivenport.TicketRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<UUID, Ticket> tickets = new HashMap<>();

    @Override
    public Optional<Ticket> find(UUID ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    @Override
    public void save(Ticket ticket) {
        tickets.put(ticket.ticketId(), ticket);
    }

}
