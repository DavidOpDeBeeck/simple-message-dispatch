package app.dodb.smd.ticket.usecase.stubs;

import app.dodb.smd.ticket.drivenport.TicketViewRepository;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class InMemoryTicketViewRepository implements TicketViewRepository {

    private final Map<UUID, TicketDTO> views = new HashMap<>();

    @Override
    public Optional<TicketDTO> find(UUID ticketId) {
        return Optional.ofNullable(views.get(ticketId));
    }

    public void stub(UUID ticketId, TicketDTO view) {
        views.put(ticketId, view);
    }

}
