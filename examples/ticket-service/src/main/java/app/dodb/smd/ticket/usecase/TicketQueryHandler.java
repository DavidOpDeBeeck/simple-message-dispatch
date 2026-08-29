package app.dodb.smd.ticket.usecase;

import app.dodb.smd.api.query.QueryHandler;
import app.dodb.smd.ticket.drivenport.TicketActivityRepository;
import app.dodb.smd.ticket.drivenport.TicketViewRepository;
import app.dodb.smd.ticket.drivingport.query.GetTicketActivityQuery;
import app.dodb.smd.ticket.drivingport.query.GetTicketQuery;
import app.dodb.smd.ticket.drivingport.transferobject.TicketActivityDTO;
import app.dodb.smd.ticket.drivingport.transferobject.TicketDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class TicketQueryHandler {

    private final TicketViewRepository views;
    private final TicketActivityRepository activity;

    public TicketQueryHandler(TicketViewRepository views,
                              TicketActivityRepository activity) {
        this.views = views;
        this.activity = activity;
    }

    @QueryHandler
    public Optional<TicketDTO> handle(GetTicketQuery query) {
        return views.find(query.ticketId());
    }

    @QueryHandler
    public List<TicketActivityDTO> handle(GetTicketActivityQuery query) {
        return activity.findAll(query.ticketId());
    }
}
