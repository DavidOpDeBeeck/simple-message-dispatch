package app.dodb.smd.eventstore.aggregate.locator.duplicate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateCommandHandler;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.ResolveTestTicketCommand;
import app.dodb.smd.eventstore.aggregate.TestTicketResolvedEvent;

import java.util.UUID;

@Aggregate("duplicate-ticket")
public class SecondDuplicateTicket {

    @AggregateId
    private UUID id;

    private SecondDuplicateTicket() {
    }

    @AggregateCommandHandler
    public SecondDuplicateTicket(ResolveTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketResolvedEvent(command.ticketId(), command.resolution()));
    }
}
