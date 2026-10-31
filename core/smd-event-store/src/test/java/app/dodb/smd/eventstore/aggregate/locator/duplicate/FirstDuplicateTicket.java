package app.dodb.smd.eventstore.aggregate.locator.duplicate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateCommandHandler;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.OpenTestTicketCommand;
import app.dodb.smd.eventstore.aggregate.TestTicketOpenedEvent;

import java.util.UUID;

@Aggregate("duplicate-ticket")
public class FirstDuplicateTicket {

    @AggregateId
    private UUID id;

    private FirstDuplicateTicket() {
    }

    @AggregateCommandHandler
    public FirstDuplicateTicket(OpenTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
    }
}
