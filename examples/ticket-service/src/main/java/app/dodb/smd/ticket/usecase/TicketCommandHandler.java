package app.dodb.smd.ticket.usecase;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.ticket.domain.Ticket;
import app.dodb.smd.ticket.drivenport.TicketRepository;
import app.dodb.smd.ticket.drivingport.command.AssignTicketCommand;
import app.dodb.smd.ticket.drivingport.command.OpenTicketCommand;
import app.dodb.smd.ticket.drivingport.command.ResolveTicketCommand;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TicketCommandHandler {

    private final TicketRepository repository;
    private final EventPublisher eventPublisher;

    public TicketCommandHandler(TicketRepository repository, EventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @CommandHandler
    public UUID handle(OpenTicketCommand command) {
        var ticketId = UUID.randomUUID();
        var ticket = new Ticket(ticketId, command.title(), command.description());

        save(ticket);

        return ticketId;
    }

    @CommandHandler
    public UUID handle(AssignTicketCommand command) {
        var ticket = repository.find(command.ticketId())
            .orElseThrow(() -> new TicketNotFoundException(command.ticketId()));

        ticket.assign(command.assignee());
        save(ticket);

        return ticket.ticketId();
    }

    @CommandHandler
    public UUID handle(ResolveTicketCommand command) {
        var ticket = repository.find(command.ticketId())
            .orElseThrow(() -> new TicketNotFoundException(command.ticketId()));

        ticket.resolve(command.resolution());
        save(ticket);

        return ticket.ticketId();
    }

    public void save(Ticket ticket) {
        repository.save(ticket);
        ticket.consumeEvents().forEach(eventPublisher::publish);
    }
}
