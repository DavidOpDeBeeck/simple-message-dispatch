package app.dodb.smd.ticket.domain;

import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateCommandHandler;
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

import static app.dodb.smd.ticket.domain.TicketStatus.ASSIGNED;
import static app.dodb.smd.ticket.domain.TicketStatus.OPEN;
import static app.dodb.smd.ticket.domain.TicketStatus.RESOLVED;

@Aggregate("ticket")
public class Ticket {

    @AggregateId
    private UUID ticketId;
    private TicketStatus status;

    private Ticket() {
    }

    @AggregateCommandHandler
    public Ticket(OpenTicketCommand command, AggregateEvents<TicketEvent> events) {
        events.apply(new TicketOpenedEvent(UUID.randomUUID(), command.title(), command.description()));
    }

    @AggregateCommandHandler
    public UUID handle(AssignTicketCommand command, AggregateEvents<TicketEvent> events) {
        requireStatus(OPEN, "assign");
        events.apply(new TicketAssignedEvent(ticketId, command.assignee()));
        return ticketId;
    }

    @AggregateCommandHandler
    public UUID handle(ResolveTicketCommand command, AggregateEvents<TicketEvent> events) {
        requireStatus(ASSIGNED, "resolve");
        events.apply(new TicketResolvedEvent(ticketId, command.resolution()));
        return ticketId;
    }

    @EventSourcingHandler
    void on(TicketOpenedEvent event) {
        ticketId = event.ticketId();
        status = OPEN;
    }

    @EventSourcingHandler
    void on(TicketAssignedEvent event) {
        status = ASSIGNED;
    }

    @EventSourcingHandler
    void on(TicketResolvedEvent event) {
        status = RESOLVED;
    }

    private void requireStatus(TicketStatus expected, String operation) {
        if (status != expected) {
            throw new InvalidTicketTransitionException(ticketId, status, operation, expected);
        }
    }
}
