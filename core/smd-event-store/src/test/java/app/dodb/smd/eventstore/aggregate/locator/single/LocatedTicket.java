package app.dodb.smd.eventstore.aggregate.locator.single;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateCommandHandler;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.AssignTestTicketCommand;
import app.dodb.smd.eventstore.aggregate.OpenTestTicketCommand;
import app.dodb.smd.eventstore.aggregate.TestTicketAssignedEvent;
import app.dodb.smd.eventstore.aggregate.TestTicketOpenedEvent;

import java.util.UUID;

@Aggregate("located-ticket")
public class LocatedTicket {

    @AggregateId
    private UUID id;

    private LocatedTicket() {
    }

    @AggregateCommandHandler
    public LocatedTicket(OpenTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
    }

    @AggregateCommandHandler
    public String handle(AssignTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketAssignedEvent(command.ticketId(), command.assignee()));
        return command.assignee();
    }
}
