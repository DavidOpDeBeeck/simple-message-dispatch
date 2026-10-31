package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

class AggregateTestFixtureTest {

    private static final UUID TICKET_ID = UUID.fromString("5b0c1f9e-8e07-4a40-9d2b-0f6f3b7f1c11");
    private static final UUID OTHER_TICKET_ID = UUID.fromString("0d7f6a52-3c3a-4a8e-a5e4-8f0f5e8f9a22");
    private static final Instant OPENED_AT = Instant.parse("2026-10-31T09:00:00Z");
    private static final Principal OPENED_BY = new SimplePrincipal(UUID.fromString("9a4c2e1b-7d3f-4b8a-9c6e-1f2a3b4c5d66"));

    private final AggregateTestFixture<TestTicket> fixture = AggregateTestFixture.of(TestTicket.class);

    @Test
    void when_creatingCommand_appliesEventsAndReturnsAggregateId() {
        fixture.givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .expectEvents(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .expectResult(TICKET_ID)
            .expectState(ticket -> ticket.extracting(TestTicket::status).isEqualTo(TestTicketStatus.OPEN));
    }

    @Test
    void when_commandAfterGivenEvents_handlesReplayedState() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"))
            .expectEvents(new TestTicketAssignedEvent(TICKET_ID, "alice"))
            .expectResult("alice")
            .expectState(ticket -> ticket.extracting(TestTicket::status).isEqualTo(TestTicketStatus.ASSIGNED));
    }

    @Test
    void when_commandAfterGivenCommands_continuesTheirStream() {
        fixture.givenCommands(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"), new AssignTestTicketCommand(TICKET_ID, "alice"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Reset mail resent"))
            .expectEvents(new TestTicketResolvedEvent(TICKET_ID, "Reset mail resent"))
            .expectState(ticket -> ticket.extracting(TestTicket::status).isEqualTo(TestTicketStatus.RESOLVED));
    }

    @Test
    void when_commandRejectedByAggregate_recordsException() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Too early"))
            .expectException(IllegalStateException.class, exception -> exception.hasMessage("Ticket cannot be resolved in status OPEN"));
    }

    @Test
    void when_commandForUnknownAggregate_recordsAggregateNotFound() {
        fixture.givenNoPriorActivity()
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"))
            .expectException(AggregateNotFoundException.class);
    }

    @Test
    void when_handlerWithRegisteredCollaborator_usesIt() {
        AggregateTestFixture.builder(FormattedTestTicket.class)
            .collaborator(TitleFormatter.class, String::toUpperCase)
            .build()
            .givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .expectEvents(new TestTicketOpenedEvent(TICKET_ID, "CANNOT SIGN IN"));
    }

    @Test
    void when_handlerTakingTimeAndPrincipal_receivesConfiguredOnes() {
        AggregateTestFixture.builder(StampedTestTicket.class)
            .time(() -> OPENED_AT)
            .principal(() -> OPENED_BY)
            .build()
            .givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .expectEvents(new TestTicketStampedEvent(TICKET_ID, OPENED_AT, OPENED_BY));
    }

    @Test
    void when_configuredEventSerializer_storesEventsThroughIt() {
        AggregateTestFixture.builder(TestTicket.class)
            .eventSerializer(new RejectingEventSerializer())
            .build()
            .givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .expectException(UnsupportedOperationException.class, exception -> exception.hasMessage("Rejected event: TestTicketOpenedEvent"));
    }

    @Test
    void when_handlerWithUnregisteredCollaborator_failsNamingIt() {
        var formattedFixture = AggregateTestFixture.of(FormattedTestTicket.class).givenNoPriorActivity();

        assertThatThrownBy(() -> formattedFixture.when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Missing aggregate collaborator")
            .hasMessageContaining(TitleFormatter.class.getName());
    }

    @Test
    void expectEvents_differentEvents_failsWithExpectedAndAppliedEvents() {
        var result = fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThatThrownBy(() -> result.expectEvents(new TestTicketAssignedEvent(TICKET_ID, "bob")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Applied aggregate events")
            .hasMessageContaining("assignee=bob")
            .hasMessageContaining("assignee=alice");
    }

    @Test
    void expectResult_failedCommand_failsWithCommandExceptionAsCause() {
        var result = fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Too early"));

        assertThatThrownBy(() -> result.expectResult(TICKET_ID))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected a result, but the command failed")
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Ticket cannot be resolved in status OPEN");
    }

    @Test
    void expectEventsSatisfying_succeededCommand_assertsAppliedEvents() {
        fixture.givenNoPriorActivity()
            .when(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .expectEventsSatisfying(events -> events.singleElement(type(TestTicketOpenedEvent.class))
                .returns("Cannot sign in", TestTicketOpenedEvent::title))
            .expectResultSatisfying(result -> result.isEqualTo(TICKET_ID));
    }

    @Test
    void expectNoEvents_failedCommand_failsWithCommandExceptionAsCause() {
        var result = fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Too early"));

        assertThatThrownBy(result::expectNoEvents)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected events, but the command failed")
            .cause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Ticket cannot be resolved in status OPEN");
    }

    @Test
    void expectState_failedCommand_failsWithCommandExceptionAsCause() {
        var result = fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Too early"));

        assertThatThrownBy(() -> result.expectState(ticket -> ticket.extracting(TestTicket::status).isEqualTo(TestTicketStatus.OPEN)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected aggregate state, but the command failed")
            .cause()
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expectException_differentType_failsNamingBothTypes() {
        var result = fixture.givenNoPriorActivity()
            .when(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThatThrownBy(() -> result.expectException(IllegalStateException.class))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Command exception")
            .hasMessageContaining(IllegalStateException.class.getName())
            .hasMessageContaining(AggregateNotFoundException.class.getName());
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
    void given_eventsAfterCommand_continueItsStream() {
        fixture.givenCommands(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"))
            .given(new TestTicketAssignedEvent(TICKET_ID, "alice"))
            .when(new ResolveTestTicketCommand(TICKET_ID, "Reset mail resent"))
            .expectEvents(new TestTicketResolvedEvent(TICKET_ID, "Reset mail resent"))
            .expectState(ticket -> ticket.extracting(TestTicket::status).isEqualTo(TestTicketStatus.RESOLVED));
    }

    @Test
    void given_eventsNotAssigningAggregateId_fails() {
        assertThatThrownBy(() -> fixture.given(new TestTicketAssignedEvent(TICKET_ID, "alice")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("an @EventSourcingHandler must assign the @AggregateId")
            .hasMessageContaining(TestTicket.class.getName());
    }

    @Test
    void given_eventWithSubjectOfAnotherAggregate_failsNamingBoth() {
        assertThatThrownBy(() -> fixture.given(
            new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"),
            new TestTicketAssignedEvent(TICKET_ID, "alice"),
            new TestTicketResolvedEvent(OTHER_TICKET_ID, "Other")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a declared @SubjectId must resolve to the aggregate's type and ID")
            .hasMessageContaining(TICKET_ID.toString())
            .hasMessageContaining(OTHER_TICKET_ID.toString());
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

    @Test
    void when_creatingCommandForAnotherAggregate_fails() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"))
            .givenCommands(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThatThrownBy(() -> fixture.when(new OpenTestTicketCommand(OTHER_TICKET_ID, "Other")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the fixture tests one aggregate")
            .hasMessageContaining(TICKET_ID.toString())
            .hasMessageContaining(OTHER_TICKET_ID.toString());
    }

    @Test
    void when_updatingCommandForAnotherAggregate_recordsAggregateNotFound() {
        fixture.given(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));

        fixture.when(new AssignTestTicketCommand(OTHER_TICKET_ID, "alice"))
            .expectException(AggregateNotFoundException.class, exception -> exception.hasMessageContaining(OTHER_TICKET_ID.toString()));
    }
}
