package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;

import java.util.UUID;

@Aggregate("test-ticket")
public class TestTicket {

    @AggregateId
    private UUID ticketId;
    private TestTicketStatus status;
    private String assignee;

    private TestTicket() {
    }

    @AggregateCommandHandler
    public TestTicket(OpenTestTicketCommand command, AggregateEvents<Event> events) {
        events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
    }

    @AggregateCommandHandler
    public String handle(AssignTestTicketCommand command, AggregateEvents<Event> events) {
        if (status != TestTicketStatus.OPEN) {
            throw new IllegalStateException("Ticket cannot be assigned in status " + status);
        }
        events.apply(new TestTicketAssignedEvent(ticketId, command.assignee()));
        return assignee;
    }

    @AggregateCommandHandler
    public UUID handle(ResolveTestTicketCommand command, AggregateEvents<Event> events) {
        if (status != TestTicketStatus.ASSIGNED) {
            throw new IllegalStateException("Ticket cannot be resolved in status " + status);
        }
        events.apply(new TestTicketResolvedEvent(ticketId, command.resolution()));
        return ticketId;
    }

    @AggregateCommandHandler
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

    TestTicketStatus status() {
        return status;
    }
}
