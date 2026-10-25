package app.dodb.smd.ticket.domain;

import app.dodb.smd.eventstore.AggregateTestFixture;
import app.dodb.smd.eventstore.aggregate.AggregateNotFoundException;
import app.dodb.smd.ticket.drivingport.command.AssignTicketCommand;
import app.dodb.smd.ticket.drivingport.command.OpenTicketCommand;
import app.dodb.smd.ticket.drivingport.command.ResolveTicketCommand;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static org.assertj.core.api.Assertions.assertThat;

class TicketTest {

    private static final UUID TICKET_ID = UUID.fromString("3f2b8c1e-6d4a-4f7b-9e21-5c8d0a7b6e13");

    private final AggregateTestFixture<Ticket> fixture = AggregateTestFixture.of(Ticket.class);

    @Test
    void openTicketCommand_noPriorActivity_startsStreamWithOpenedEvent() {
        fixture.givenNoPriorActivity()
            .when(new OpenTicketCommand(TICKET_TITLE, TICKET_DESCRIPTION))
            .expectResultSatisfying(ticketId -> assertThat(ticketId).isNotNull())
            .expectEventsSatisfying(events -> assertThat(events).singleElement()
                .isInstanceOfSatisfying(TicketOpenedEvent.class, event -> {
                    assertThat(event.title()).isEqualTo(TICKET_TITLE);
                    assertThat(event.description()).isEqualTo(TICKET_DESCRIPTION);
                }));
    }

    @Test
    void assignTicketCommand_openTicket_appendsAssignedEvent() {
        fixture.given(new TicketOpenedEvent(TICKET_ID, TICKET_TITLE, TICKET_DESCRIPTION))
            .when(new AssignTicketCommand(TICKET_ID, TICKET_ASSIGNEE))
            .expectEvents(new TicketAssignedEvent(TICKET_ID, TICKET_ASSIGNEE))
            .expectResult(TICKET_ID);
    }

    @Test
    void resolveTicketCommand_assignedTicket_appendsResolvedEvent() {
        fixture.given(
                new TicketOpenedEvent(TICKET_ID, TICKET_TITLE, TICKET_DESCRIPTION),
                new TicketAssignedEvent(TICKET_ID, TICKET_ASSIGNEE))
            .when(new ResolveTicketCommand(TICKET_ID, TICKET_RESOLUTION))
            .expectEvents(new TicketResolvedEvent(TICKET_ID, TICKET_RESOLUTION))
            .expectResult(TICKET_ID);
    }

    @Test
    void resolveTicketCommand_openTicket_rejectsTransitionWithoutEvents() {
        fixture.given(new TicketOpenedEvent(TICKET_ID, TICKET_TITLE, TICKET_DESCRIPTION))
            .when(new ResolveTicketCommand(TICKET_ID, TICKET_RESOLUTION))
            .expectException(InvalidTicketTransitionException.class,
                exception -> assertThat(exception).hasMessageContaining("expectedStatus=ASSIGNED, actualStatus=OPEN"))
            .expectNoEvents();
    }

    @Test
    void assignTicketCommand_unknownTicket_throwsAggregateNotFound() {
        fixture.givenNoPriorActivity()
            .when(new AssignTicketCommand(TICKET_ID, TICKET_ASSIGNEE))
            .expectException(AggregateNotFoundException.class,
                exception -> assertThat(exception).hasMessageContaining(TICKET_ID.toString()))
            .expectNoEvents();
    }
}
