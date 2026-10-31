package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.metadata.principal.Principal;

import java.time.Instant;
import java.util.UUID;

@Aggregate("stamped-test-ticket")
public class StampedTestTicket {

    @AggregateId
    private UUID ticketId;

    private StampedTestTicket() {
    }

    @AggregateCommandHandler
    public StampedTestTicket(OpenTestTicketCommand command, Instant openedAt, Principal openedBy, AggregateEvents<Event> events) {
        events.apply(new TestTicketStampedEvent(command.ticketId(), openedAt, openedBy));
    }

    @EventSourcingHandler
    void on(TestTicketStampedEvent event) {
        ticketId = event.ticketId();
    }
}
