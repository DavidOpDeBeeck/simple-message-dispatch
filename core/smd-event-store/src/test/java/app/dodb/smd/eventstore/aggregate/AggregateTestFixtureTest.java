package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.eventstore.AggregateTestFixture;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateTestFixtureTest {

    private static final UUID TICKET_ID = UUID.fromString("5b0c1f9e-8e07-4a40-9d2b-0f6f3b7f1c11");
    private static final UUID OTHER_TICKET_ID = UUID.fromString("0d7f6a52-3c3a-4a8e-a5e4-8f0f5e8f9a22");

    private final AggregateTestFixture<TestTicket> fixture = AggregateTestFixture.of(TestTicket.class);

    @Test
    void when_creatingCommand_appliesEventsAndReturnsAggregateId() {
        fixture.givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .expectEvents(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .expectResult(TICKET_ID)
            .expectState(ticket -> assertThat(ticket.status()).isEqualTo(TestTicketStatus.OPEN));
    }

    @Test
    void when_commandAfterGivenEvents_handlesReplayedState() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"))
            .expectEvents(new TestTicketAssignedEvent(TICKET_ID, "alice"))
            .expectResult("alice")
            .expectState(ticket -> assertThat(ticket.status()).isEqualTo(TestTicketStatus.ASSIGNED));
    }

    @Test
    void when_commandAfterGivenCommands_continuesTheirStream() {
        fixture.givenCommands(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"), new AssignTestTicketCommand(TICKET_ID, "alice"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Reset mail resent"))
            .expectEvents(new TestTicketResolvedEvent(TICKET_ID, "Reset mail resent"))
            .expectState(ticket -> assertThat(ticket.status()).isEqualTo(TestTicketStatus.RESOLVED));
    }

    @Test
    void when_commandRejectedByAggregate_recordsExceptionAndNoEvents() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Too early"))
            .expectException(IllegalStateException.class,
                exception -> assertThat(exception).hasMessage("Ticket cannot be resolved in status OPEN"))
            .expectNoEvents()
            .expectState(ticket -> assertThat(ticket.status()).isEqualTo(TestTicketStatus.OPEN));
    }

    @Test
    void when_commandForUnknownAggregate_recordsAggregateNotFound() {
        fixture.givenNoPriorActivity()
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"))
            .expectException(AggregateNotFoundException.class)
            .expectNoEvents();
    }

    @Test
    void expectEvents_differentEvents_failsWithExpectedAndAppliedEvents() {
        var result = fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThatThrownBy(() -> result.expectEvents(new TestTicketAssignedEvent(TICKET_ID, "bob")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Unexpected aggregate events")
            .hasMessageContaining("assignee=bob")
            .hasMessageContaining("assignee=alice");
    }

    @Test
    void expectResult_failedCommand_failsWithCommandExceptionAsCause() {
        var result = fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Too early"));

        assertThatThrownBy(() -> result.expectResult(TICKET_ID))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("the command failed")
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Ticket cannot be resolved in status OPEN");
    }

    @Test
    void expectException_succeededCommand_fails() {
        var result = fixture.givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThatThrownBy(() -> result.expectException(IllegalStateException.class))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected the command to fail, but it succeeded")
            .hasMessageContaining(IllegalStateException.class.getName());
    }

    @Test
    void given_eventsNotAssigningAggregateId_fails() {
        assertThatThrownBy(() -> fixture.given(new TestTicketAssignedEvent(TICKET_ID, "alice")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("an @EventSourcingHandler must assign the @AggregateId")
            .hasMessageContaining(TestTicket.class.getName());
    }

    @Test
    void given_eventsOfAnotherAggregate_fails() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));

        assertThatThrownBy(() -> fixture.given(new TestTicketOpenedEvent(OTHER_TICKET_ID, "Other")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the fixture tests one aggregate")
            .hasMessageContaining(TICKET_ID.toString())
            .hasMessageContaining(OTHER_TICKET_ID.toString());
    }
}
