package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;

import java.util.UUID;

@Aggregate("formatted-test-ticket")
public class FormattedTestTicket {

    @AggregateId
    private UUID ticketId;

    private FormattedTestTicket() {
    }

    @AggregateCommandHandler
    public FormattedTestTicket(OpenTestTicketCommand command, TitleFormatter formatter, AggregateEvents<Event> events) {
        events.apply(new TestTicketOpenedEvent(command.ticketId(), formatter.format(command.title())));
    }

    @EventSourcingHandler
    void on(TestTicketOpenedEvent event) {
        ticketId = event.ticketId();
    }
}
