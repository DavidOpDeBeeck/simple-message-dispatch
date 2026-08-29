package app.dodb.smd.ticket.usecase.stubs;

import app.dodb.smd.ticket.drivenport.TicketActivityRepository;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class InMemoryTicketActivityRepository implements TicketActivityRepository {

    private final Map<UUID, List<TicketActivityDTO>> activityByTicket = new HashMap<>();

    @Override
    public List<TicketActivityDTO> findAll(UUID ticketId) {
        return activityByTicket.getOrDefault(ticketId, List.of());
    }

    public void stub(UUID ticketId, List<TicketActivityDTO> activities) {
        activityByTicket.put(ticketId, activities);
    }

}
