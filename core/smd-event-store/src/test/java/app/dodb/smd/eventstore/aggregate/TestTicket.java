package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;

import java.util.UUID;

@Aggregate("test-ticket")
public class TestTicket {

    @AggregateId
    private UUID ticketId;
    private TestTicketStatus status;
    private String assignee;

    protected TestTicket() {
    }

    @CommandHandler
    public UUID handle(OpenTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        return ticketId;
    }

    @CommandHandler
    public String handle(AssignTestTicketCommand command, AggregateEvents<Event> events) {
        if (status != TestTicketStatus.OPEN) {
            throw new IllegalStateException("Ticket cannot be assigned in status " + status);
        }
        events.apply(new TestTicketAssignedEvent(ticketId, command.assignee()));
        return assignee;
    }

    @CommandHandler
    public UUID handle(ResolveTestTicketCommand command, AggregateEvents<Event> events) {
        if (status != TestTicketStatus.ASSIGNED) {
            throw new IllegalStateException("Ticket cannot be resolved in status " + status);
        }
        events.apply(new TestTicketResolvedEvent(ticketId, command.resolution()));
        return ticketId;
    }

    @CommandHandler
    public UUID handle(MisdirectTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketMisdirectedEvent(command.otherTicketId()));
        return ticketId;
    }

    @EventSourcingHandler
    void on(TestTicketOpenedEvent event) {
        ticketId = event.ticketId();
        status = TestTicketStatus.OPEN;
    }

    @EventSourcingHandler
    void on(TestTicketAssignedEvent event) {
        assignee = event.assignee();
        status = TestTicketStatus.ASSIGNED;
    }

    @EventSourcingHandler
    void on(TestTicketResolvedEvent event) {
        status = TestTicketStatus.RESOLVED;
    }
}
