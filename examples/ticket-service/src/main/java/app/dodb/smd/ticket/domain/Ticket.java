package app.dodb.smd.ticket.domain;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.EventSourcingHandler;
import app.dodb.smd.ticket.drivingport.command.AssignTicketCommand;
import app.dodb.smd.ticket.drivingport.command.OpenTicketCommand;
import app.dodb.smd.ticket.drivingport.command.ResolveTicketCommand;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;

import java.util.UUID;

@Aggregate("ticket")
public class Ticket {

    @AggregateId
    private UUID ticketId;
    private TicketStatus status;

    protected Ticket() {
    }

    @CommandHandler
    public UUID handle(OpenTicketCommand command, AggregateEvents<TicketEvent> events) {
        events.apply(new TicketOpenedEvent(UUID.randomUUID(), command.title(), command.description()));
        return ticketId;
    }

    @CommandHandler
    public UUID handle(AssignTicketCommand command, AggregateEvents<TicketEvent> events) {
        requireStatus(TicketStatus.OPEN, "assign");
        events.apply(new TicketAssignedEvent(ticketId, command.assignee()));
        return ticketId;
    }

    @CommandHandler
    public UUID handle(ResolveTicketCommand command, AggregateEvents<TicketEvent> events) {
        requireStatus(TicketStatus.ASSIGNED, "resolve");
        events.apply(new TicketResolvedEvent(ticketId, command.resolution()));
        return ticketId;
    }

    @EventSourcingHandler
    void on(TicketOpenedEvent event) {
        ticketId = event.ticketId();
        status = TicketStatus.OPEN;
    }

    @EventSourcingHandler
    void on(TicketAssignedEvent event) {
        status = TicketStatus.ASSIGNED;
    }

    @EventSourcingHandler
    void on(TicketResolvedEvent event) {
        status = TicketStatus.RESOLVED;
    }

    private void requireStatus(TicketStatus expected, String operation) {
        if (status != expected) {
            throw new InvalidTicketTransitionException(ticketId, status, operation, expected);
        }
    }
}
