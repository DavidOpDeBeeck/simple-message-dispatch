package app.dodb.smd.ticket.domain;

import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.ProcessingGroupRegistry;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.aggregate.AggregateNotFoundException;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import app.dodb.smd.test.NoOpTransactionProvider;
import app.dodb.smd.ticket.drivingport.command.AssignTicketCommand;
import app.dodb.smd.ticket.drivingport.command.OpenTicketCommand;
import app.dodb.smd.ticket.drivingport.command.ResolveTicketCommand;
import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_ASSIGNEE;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_DESCRIPTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_RESOLUTION;
import static app.dodb.smd.ticket.test.TicketTestConstants.TICKET_TITLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final EventSerializer eventSerializer = new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
    private final EventStore eventStore = new EventStore(EventStoreConfig.withDefaults()
        .transactionProvider(new NoOpTransactionProvider())
        .eventStorage(eventStorage)
        .eventSerializer(eventSerializer)
        .tokenStore(new InMemoryTokenStore())
        .eventSequenceStore(new InMemoryEventSequenceStore())
        .build());
    private final CommandBus commandBus = CommandBusSpec.withDefaults()
        .commandHandlers(eventStore.aggregateCommandHandlers(List.of(Ticket.class), EventBusSpec.withDefaults()
            .sinks(eventStore.inlet())
            .processingGroups(ProcessingGroupRegistry::empty)
            .create()))
        .create();

    @AfterEach
    void closeEventStore() {
        eventStore.close();
    }

    @Test
    void send_openTicketCommand_startsStreamWithOpenedEvent() {
        var ticketId = commandBus.send(new OpenTicketCommand(TICKET_TITLE, TICKET_DESCRIPTION));

        assertThat(stream(ticketId)).containsExactly(new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION));
    }

    @Test
    void send_assignTicketCommand_appendsAssignedEventToOpenTicket() {
        var ticketId = commandBus.send(new OpenTicketCommand(TICKET_TITLE, TICKET_DESCRIPTION));

        var result = commandBus.send(new AssignTicketCommand(ticketId, TICKET_ASSIGNEE));

        assertThat(result).isEqualTo(ticketId);
        assertThat(stream(ticketId)).containsExactly(
            new TicketOpenedEvent(ticketId, TICKET_TITLE, TICKET_DESCRIPTION),
            new TicketAssignedEvent(ticketId, TICKET_ASSIGNEE)
        );
    }

    @Test
    void send_resolveTicketCommand_appendsResolvedEventToAssignedTicket() {
        var ticketId = commandBus.send(new OpenTicketCommand(TICKET_TITLE, TICKET_DESCRIPTION));
        commandBus.send(new AssignTicketCommand(ticketId, TICKET_ASSIGNEE));

        var result = commandBus.send(new ResolveTicketCommand(ticketId, TICKET_RESOLUTION));

        assertThat(result).isEqualTo(ticketId);
        assertThat(stream(ticketId)).last().isEqualTo(new TicketResolvedEvent(ticketId, TICKET_RESOLUTION));
    }

    @Test
    void send_resolveTicketCommand_rejectsOpenTicketWithoutAppending() {
        var ticketId = commandBus.send(new OpenTicketCommand(TICKET_TITLE, TICKET_DESCRIPTION));

        assertThatThrownBy(() -> commandBus.send(new ResolveTicketCommand(ticketId, TICKET_RESOLUTION)))
            .isInstanceOf(InvalidTicketTransitionException.class)
            .hasMessageContaining("expectedStatus=ASSIGNED, actualStatus=OPEN");

        assertThat(stream(ticketId)).hasSize(1);
    }

    @Test
    void send_assignTicketCommand_rejectsUnknownTicket() {
        var ticketId = UUID.randomUUID();

        assertThatThrownBy(() -> commandBus.send(new AssignTicketCommand(ticketId, TICKET_ASSIGNEE)))
            .isInstanceOf(AggregateNotFoundException.class)
            .hasMessageContaining(ticketId.toString());
    }

    private List<Event> stream(UUID ticketId) {
        return eventStorage.loadAggregateStream("ticket", ticketId.toString()).stream()
            .map(stored -> (Event) eventSerializer.deserialize(new SerializedEvent(stored.eventId(), stored.globalPosition(), stored.aggregateId(),
                stored.eventType(), stored.payload(), stored.metadata(), stored.occurredAt())).payload())
            .toList();
    }
}
