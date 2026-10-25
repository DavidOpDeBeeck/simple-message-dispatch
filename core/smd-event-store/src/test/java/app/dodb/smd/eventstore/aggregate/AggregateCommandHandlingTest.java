package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateCommandHandlingTest {

    private static final UUID TICKET_ID = UUID.fromString("5b0c1f9e-8e07-4a40-9d2b-0f6f3b7f1c11");
    private static final UUID OTHER_TICKET_ID = UUID.fromString("0d7f6a52-3c3a-4a8e-a5e4-8f0f5e8f9a22");

    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final EventSerializer eventSerializer = new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
    private final DeferringTransactionProvider transactionProvider = new DeferringTransactionProvider();
    private final TestTicketProjection projection = new TestTicketProjection();
    private final EventStore eventStore = new EventStore(EventStoreConfig.withDefaults()
        .transactionProvider(transactionProvider)
        .eventStorage(eventStorage)
        .eventSerializer(eventSerializer)
        .tokenStore(new InMemoryTokenStore())
        .eventSequenceStore(new InMemoryEventSubjectSequenceStore())
        .build());
    private final CommandBus commandBus = commandBus(true);

    @AfterEach
    void closeEventStore() {
        eventStore.close();
    }

    @Test
    void send_commandWithoutTargetAggregateId_createsStreamAtVersionOne() {
        UUID result = commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThat(result).isEqualTo(TICKET_ID);
        assertThat(stream(TICKET_ID)).containsExactly(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));
        assertThat(eventStorage.events()).singleElement()
            .satisfies(stored -> assertThat(stored.subjectId()).contains(TICKET_ID.toString()));
    }

    @Test
    void send_commandWithTargetAggregateId_replaysStreamBeforeHandling() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));
        commandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice"));

        UUID result = commandBus.send(new ResolveTestTicketCommand(TICKET_ID, "Reset mail resent"));

        assertThat(result).isEqualTo(TICKET_ID);
        assertThat(stream(TICKET_ID)).containsExactly(
            new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"),
            new TestTicketAssignedEvent(TICKET_ID, "alice"),
            new TestTicketResolvedEvent(TICKET_ID, "Reset mail resent")
        );
    }

    @Test
    void send_applyingEvent_updatesAggregateStateImmediately() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        String assignee = commandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThat(assignee).isEqualTo("alice");
    }

    @Test
    void send_rejectedByAggregate_storesNoEvents() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThatThrownBy(() -> commandBus.send(new ResolveTestTicketCommand(TICKET_ID, "Too early")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Ticket cannot be resolved in status OPEN");

        assertThat(stream(TICKET_ID)).hasSize(1);
    }

    @Test
    void send_unknownTargetAggregate_throwsAggregateNotFound() {
        assertThatThrownBy(() -> commandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice")))
            .isInstanceOfSatisfying(AggregateNotFoundException.class, exception -> {
                assertThat(exception.aggregateType()).isEqualTo("test-ticket");
                assertThat(exception.aggregateId()).isEqualTo(TICKET_ID.toString());
            });

        assertThat(eventStorage.events()).isEmpty();
    }

    @Test
    void send_staleAggregateVersion_throwsConcurrentAggregateChange() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        // Both commands load version 1 because event storage is deferred until the shared transaction completes.
        assertThatThrownBy(() -> transactionProvider.doInTransaction(() -> {
            commandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice"));
            commandBus.send(new AssignTestTicketCommand(TICKET_ID, "bob"));
        }))
            .isInstanceOfSatisfying(ConcurrentAggregateChangeException.class, exception -> {
                assertThat(exception.aggregateType()).isEqualTo("test-ticket");
                assertThat(exception.aggregateId()).isEqualTo(TICKET_ID.toString());
                assertThat(exception.expectedVersion()).isEqualTo(1L);
            });

        assertThat(stream(TICKET_ID)).containsExactly(
            new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"),
            new TestTicketAssignedEvent(TICKET_ID, "alice")
        );
    }

    @Test
    void send_aggregateEvent_reachesProcessingGroupsWithCommandAsParent() {
        var command = CommandMessage.from(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"),
            new Metadata(null, Instant.parse("2026-10-25T10:00:00Z"), null));

        commandBus.send(command);

        assertThat(projection.events()).containsExactly(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));
        assertThat(projection.metadata()).singleElement()
            .satisfies(metadata -> assertThat(metadata.parentMessageId()).isEqualTo(command.messageId()));
    }

    @Test
    void send_eventWithForeignSubject_failsAndStoresNothing() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThatThrownBy(() -> commandBus.send(new MisdirectTestTicketCommand(TICKET_ID, OTHER_TICKET_ID)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@SubjectId must resolve to the aggregate ID")
            .hasMessageContaining(OTHER_TICKET_ID.toString());

        assertThat(eventStorage.events()).hasSize(1);
    }

    @Test
    void send_withoutEventStoreSink_failsInsteadOfDroppingEvents() {
        var commandBusWithoutSink = commandBus(false);

        assertThatThrownBy(() -> commandBusWithoutSink.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Aggregate event was not stored");
    }

    @Test
    void locate_aggregateWithoutTypeName_fails() {
        var locator = eventStore.aggregateCommandHandlers(List.of(UnnamedAggregate.class), eventBus(true));

        assertThatThrownBy(locator::locate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@Aggregate and a non-blank, stable type name")
            .hasMessageContaining(UnnamedAggregate.class.getName());
    }

    @Test
    void locate_aggregateWithoutAggregateId_fails() {
        var locator = eventStore.aggregateCommandHandlers(List.of(AggregateWithoutId.class), eventBus(true));

        assertThatThrownBy(locator::locate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one non-static @AggregateId field")
            .hasMessageContaining(AggregateWithoutId.class.getName());
    }

    private CommandBus commandBus(boolean withEventStoreSink) {
        return CommandBusSpec.withDefaults()
            .commandHandlers(eventStore.aggregateCommandHandlers(List.of(TestTicket.class), eventBus(withEventStoreSink)))
            .create();
    }

    private EventBus eventBus(boolean withEventStoreSink) {
        var spec = EventBusSpec.withDefaults().processingGroups(() -> AnnotatedEventHandler.from(projection));
        if (withEventStoreSink) {
            spec.sinks(eventStore.inlet());
        }
        return spec.create();
    }

    private List<Event> stream(UUID ticketId) {
        return eventStorage.loadAggregateStream("test-ticket", ticketId.toString()).stream()
            .map(serializedEvent -> (Event) eventSerializer.deserialize(serializedEvent).payload())
            .toList();
    }

    public static class UnnamedAggregate {

        @AggregateId
        private UUID id;

        @CommandHandler
        public UUID handle(OpenTestTicketCommand command) {
            return id;
        }
    }

    @Aggregate("without-id")
    public static class AggregateWithoutId {

        @CommandHandler
        public UUID handle(OpenTestTicketCommand command) {
            return null;
        }
    }

    // Joins an outer transaction and runs deferred work when the outermost transaction completes, like SpringTransactionProvider.
    private static class DeferringTransactionProvider implements TransactionProvider {

        private List<Runnable> deferredWork;

        @Override
        public void defer(Runnable runnable) {
            if (deferredWork == null) {
                runnable.run();
                return;
            }
            deferredWork.add(runnable);
        }

        @Override
        public <T> T doInTransaction(Supplier<T> supplier) {
            if (deferredWork != null) {
                return supplier.get();
            }

            deferredWork = new ArrayList<>();
            try {
                var result = supplier.get();
                deferredWork.forEach(Runnable::run);
                return result;
            } finally {
                deferredWork = null;
            }
        }

        @Override
        public void doInTransaction(Runnable runnable) {
            doInTransaction(() -> {
                runnable.run();
                return null;
            });
        }

        @Override
        public <T> T doInNewTransaction(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void doInNewTransaction(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }
    }
}
