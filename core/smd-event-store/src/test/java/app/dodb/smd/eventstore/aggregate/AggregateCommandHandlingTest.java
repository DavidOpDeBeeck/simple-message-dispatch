package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.AnnotatedCommandHandler;
import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static app.dodb.smd.eventstore.EventStoreMetadata.SUBJECT_VERSION;
import static app.dodb.smd.eventstore.EventStoreMetadata.subjectVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateCommandHandlingTest {

    private static final UUID TICKET_ID = UUID.fromString("5b0c1f9e-8e07-4a40-9d2b-0f6f3b7f1c11");
    private static final UUID OTHER_TICKET_ID = UUID.fromString("0d7f6a52-3c3a-4a8e-a5e4-8f0f5e8f9a22");

    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final EventSerializer eventSerializer = new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build(), new ClassNameEventTypeResolver(), List.of());
    private final DeferringTransactionProvider transactionProvider = new DeferringTransactionProvider();
    private final TestTicketProjection projection = new TestTicketProjection();
    private final EventStore eventStore = new EventStore(EventStoreConfig.withDefaults()
        .transactionProvider(transactionProvider)
        .eventStorage(eventStorage)
        .eventSerializer(eventSerializer)
        .tokenStore(new InMemoryTokenStore())
        .eventSequenceStore(new InMemoryEventSequenceStore())
        .build());
    private final StubObjectCreator objectCreator = new StubObjectCreator(Map.of(TitleFormatter.class, new UpperCaseTitleFormatter()));
    private final CommandBus commandBus = commandBus(TestTicket.class);

    @AfterEach
    void closeEventStore() {
        eventStore.close();
    }

    @Test
    void send_creatingCommand_createsStreamAtVersionOneAndReturnsAggregateId() {
        UUID result = commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThat(result).isEqualTo(TICKET_ID);
        assertThat(stream(TICKET_ID)).containsExactly(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));
        assertThat(eventStorage.events()).singleElement()
            .satisfies(stored -> {
                assertThat(stored.subject()).contains(new Subject("test-ticket", TICKET_ID.toString()));
                assertThat(stored.subjectVersion()).contains(1L);
            });
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
    void send_creatingCommandForExistingAggregate_throwsConcurrentAggregateChange() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThatThrownBy(() -> commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Opened twice")))
            .isInstanceOfSatisfying(ConcurrentAggregateChangeException.class, exception -> {
                assertThat(exception.aggregateId()).isEqualTo(TICKET_ID.toString());
                assertThat(exception.expectedVersion()).isZero();
            });

        assertThat(stream(TICKET_ID)).containsExactly(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));
    }

    @Test
    void send_creatingConstructorApplyingNoEvents_failsAndStoresNothing() {
        var eventlessCommandBus = commandBus(EventlessAggregate.class);

        assertThatThrownBy(() -> eventlessCommandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no events were applied")
            .hasMessageContaining(EventlessAggregate.class.getName());

        assertThat(eventStorage.events()).isEmpty();
    }

    @Test
    void send_creatingConstructorNotAssigningAggregateId_failsAndStoresNothing() {
        var unassignedCommandBus = commandBus(AggregateNotAssigningId.class);

        assertThatThrownBy(() -> unassignedCommandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@AggregateId is null after applying the creation events")
            .hasMessageContaining(AggregateNotAssigningId.class.getName());

        assertThat(eventStorage.events()).isEmpty();
    }

    @Test
    void send_applyingEvent_updatesAggregateStateImmediately() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        String assignee = commandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThat(assignee).isEqualTo("alice");
    }

    @Test
    void send_eventSourcingHandlerWithMessageParameters_seesStoredMessageWhenAppliedAndReplayed() {
        var observedCommandBus = commandBus(ObservedTicket.class);
        observedCommandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        String applied = observedCommandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice"));

        var stored = storedMessages(TICKET_ID).getLast();
        assertThat(applied).isEqualTo(stored.messageId() + "@" + stored.metadata().timestamp());
        assertThat(observedCommandBus.send(new ObserveTestTicketCommand(TICKET_ID))).isEqualTo(applied);
    }

    @Test
    void send_eventMatchingSeveralEventSourcingHandlers_appliesEachInOrder() {
        var orderedCommandBus = commandBus(OrderedTicket.class);
        orderedCommandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        String applied = orderedCommandBus.send(new ObserveTestTicketCommand(TICKET_ID));

        assertThat(applied).isEqualTo("opened, any");
    }

    @Test
    void send_handlersWithCollaborator_receiveItFromObjectCreator() {
        var collaboratingCommandBus = commandBus(CollaboratingTicket.class);
        collaboratingCommandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        String assignee = collaboratingCommandBus.send(new AssignTestTicketCommand(TICKET_ID, "alice"));

        assertThat(assignee).isEqualTo("ALICE");
        assertThat(stream(TICKET_ID)).containsExactly(
            new TestTicketOpenedEvent(TICKET_ID, "CANNOT SIGN IN"),
            new TestTicketAssignedEvent(TICKET_ID, "ALICE")
        );
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
    void send_nullTargetAggregateId_failsWithCommandAndAccessor() {
        assertThatThrownBy(() -> commandBus.send(new AssignTestTicketCommand(null, "alice")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@TargetAggregateId resolved to null")
            .hasMessageContaining(AssignTestTicketCommand.class.getName());
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
            Metadata.at(Instant.parse("2026-10-25T10:00:00Z")));

        commandBus.send(command);

        assertThat(projection.events()).containsExactly(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"));
        assertThat(projection.metadata()).singleElement()
            .satisfies(metadata -> assertThat(metadata.parentMessageId()).isEqualTo(command.messageId()));
    }

    @Test
    void send_eventWithForeignSubjectId_failsAndStoresNothing() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThatThrownBy(() -> commandBus.send(new MisdirectTestTicketCommand(TICKET_ID, OTHER_TICKET_ID)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a declared @SubjectId must resolve to the aggregate's type and ID")
            .hasMessageContaining(OTHER_TICKET_ID.toString());

        assertThat(eventStorage.events()).hasSize(1);
    }

    @Test
    void send_aggregateEvent_reachesProcessingGroupsWithSubjectVersion() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThat(projection.metadata()).singleElement()
            .satisfies(metadata -> assertThat(subjectVersion(metadata)).contains(1L));
    }

    @Test
    void send_aggregateEvent_storesMetadataWithoutSubjectVersion() {
        commandBus.send(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"));

        assertThat(storedMessages(TICKET_ID)).singleElement()
            .satisfies(stored -> assertThat(stored.metadata().property(SUBJECT_VERSION)).isEmpty());
    }

    @Test
    void publish_subjectVersionAsStoredProperty_storesUnversionedEvent() {
        var eventMessage = new EventMessage<>(MessageId.generate(), Optional.of(new Subject("test-ticket", TICKET_ID.toString())),
            new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"), Metadata.at(Instant.now()).withProperty(SUBJECT_VERSION, "1"));

        eventBus().publish(eventMessage);

        assertThat(eventStorage.events()).singleElement()
            .satisfies(stored -> assertThat(stored.eventId()).isEqualTo(eventMessage.messageId()));
        assertThat(stream(TICKET_ID)).isEmpty();
    }

    @Test
    void publish_subjectVersionWithoutSubject_failsWithoutStoring() {
        var eventMessage = EventMessage.from(new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"), Metadata.at(Instant.now())
            .withTransientProperty(SUBJECT_VERSION, "1"));

        assertThatThrownBy(() -> eventBus().publish(eventMessage))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a subject version requires a subject")
            .hasMessageContaining(eventMessage.messageId().value().toString());

        assertThat(eventStorage.events()).isEmpty();
    }

    @Test
    void update_outsideCommandHandling_appliesEventsToReplayedAggregate() {
        var repository = aggregates().repository(TestTicket.class);
        repository.create(events -> new TestTicket(new OpenTestTicketCommand(TICKET_ID, "Cannot sign in"), events));

        String assignee = repository.update(TICKET_ID, (ticket, events) -> ticket.handle(new AssignTestTicketCommand(TICKET_ID, "alice"), events));

        assertThat(assignee).isEqualTo("alice");
        assertThat(stream(TICKET_ID)).containsExactly(
            new TestTicketOpenedEvent(TICKET_ID, "Cannot sign in"),
            new TestTicketAssignedEvent(TICKET_ID, "alice")
        );
    }

    @Test
    void repository_classWithoutAggregateAnnotation_fails() {
        var aggregates = aggregates();

        assertThatThrownBy(() -> aggregates.repository(UnnamedAggregate.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("annotated with @Aggregate")
            .hasMessageContaining(UnnamedAggregate.class.getName());
    }

    @Test
    void repository_aggregateWithoutAggregateId_fails() {
        var aggregates = aggregates();

        assertThatThrownBy(() -> aggregates.repository(AggregateWithoutAggregateId.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one non-static @AggregateId field")
            .hasMessageContaining(AggregateWithoutAggregateId.class.getName());
    }

    @Test
    void repository_eventSourcingHandlerWithUnsupportedParameter_fails() {
        var aggregates = aggregates();

        assertThatThrownBy(() -> aggregates.repository(AggregateWithUnsupportedEventSourcingParameter.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported parameter types")
            .hasMessageContaining(AggregateWithUnsupportedEventSourcingParameter.class.getName());
    }

    @Test
    void repository_staticEventSourcingHandler_fails() {
        var aggregates = aggregates();

        assertThatThrownBy(() -> aggregates.repository(AggregateWithStaticEventSourcingHandler.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must not be static.")
            .hasMessageContaining(AggregateWithStaticEventSourcingHandler.class.getName());
    }

    @Test
    void repository_overlappingEventSourcingHandlersWithSameOrder_fails() {
        var aggregates = aggregates();

        assertThatThrownBy(() -> aggregates.repository(AggregateWithSameOrderHandlers.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Event sourcing handlers with same order found")
            .hasMessageContaining(AggregateWithSameOrderHandlers.class.getName());
    }

    @Test
    void from_aggregateWithCommandHandlerAnnotation_fails() {
        var repository = aggregates().repository(AggregateWithPlainCommandHandler.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@AggregateCommandHandler instead of @CommandHandler")
            .hasMessageContaining(AggregateWithPlainCommandHandler.class.getName());
    }

    @Test
    void from_updatingHandlersWithoutCreatingConstructor_fails() {
        var repository = aggregates().repository(AggregateWithoutCreatingConstructor.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("an @AggregateCommandHandler constructor must create the aggregate")
            .hasMessageContaining(AggregateWithoutCreatingConstructor.class.getName());
    }

    @Test
    void from_commandWithoutTargetAggregateId_fails() {
        var repository = aggregates().repository(AggregateWithUntargetedCommand.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the command must declare @TargetAggregateId")
            .hasMessageContaining(ReopenTestTicketCommand.class.getName());
    }

    @Test
    void from_creatingCommandWithTargetAggregateId_fails() {
        var repository = aggregates().repository(AggregateWithTargetedCreation.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a creating command must not declare @TargetAggregateId")
            .hasMessageContaining(ResolveTestTicketCommand.class.getName());
    }

    @Test
    void from_collaboratorTheObjectCreatorCannotProvide_failsWithItsError() {
        var repository = aggregates().repository(CollaboratingTicket.class);
        var objectCreator = new StubObjectCreator(Map.of());

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(TitleFormatter.class.getName());
    }

    @Test
    void from_creatingCommandNotReturningAggregateId_fails() {
        var repository = aggregates().repository(AggregateWithWrongCreationResult.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a creating command must return the @AggregateId")
            .hasMessageContaining(AggregateWithWrongCreationResult.class.getName());
    }

    @Test
    void from_updatingHandlerWithOtherReturnTypeThanCommand_fails() {
        var repository = aggregates().repository(AggregateWithWrongUpdateResult.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: return type mismatch.")
            .hasMessageContaining(AggregateWithWrongUpdateResult.class.getName());
    }

    @Test
    void from_staticUpdatingHandler_fails() {
        var repository = aggregates().repository(AggregateWithStaticUpdatingHandler.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must not be static.")
            .hasMessageContaining(AggregateWithStaticUpdatingHandler.class.getName());
    }

    @Test
    void from_targetAggregateIdOfOtherTypeThanAggregateId_fails() {
        var repository = aggregates().repository(AggregateWithMistypedTargetAggregateId.class);

        assertThatThrownBy(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("the @TargetAggregateId must have the type of the @AggregateId")
            .hasMessageContaining(AggregateWithMistypedTargetAggregateId.class.getName())
            .hasMessageContaining(String.class.getName());
    }

    @Test
    void and_commandHandlerForAggregateCommand_failsNamingBothHandlers() {
        var aggregateHandlers = AnnotatedAggregateCommandHandler.from(aggregates().repository(TestTicket.class), objectCreator);
        var plainHandlers = AnnotatedCommandHandler.from(new PlainOpenTestTicketHandler());

        assertThatThrownBy(() -> aggregateHandlers.and(plainHandlers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Ambiguous command handlers found")
            .hasMessageContaining("@AggregateCommandHandler " + TestTicket.class.getName() + "(OpenTestTicketCommand, AggregateEvents)")
            .hasMessageContaining("@CommandHandler " + PlainOpenTestTicketHandler.class.getName() + "#handle(OpenTestTicketCommand)");
    }

    private CommandBus commandBus(Class<?> aggregateClass) {
        var repository = aggregates().repository(aggregateClass);
        return CommandBusSpec.withDefaults()
            .commandHandlers(() -> AnnotatedAggregateCommandHandler.from(repository, objectCreator))
            .create();
    }

    private EventSourcedAggregates aggregates() {
        return new EventSourcedAggregates(eventStore, eventBus(), new MetadataFactory(new SimplePrincipalProvider(), new SystemTimeProvider()));
    }

    private EventBus eventBus() {
        return EventBusSpec.withDefaults()
            .processingGroups(() -> AnnotatedEventHandler.from(projection))
            .sinks(eventStore.inlet())
            .create();
    }

    private List<Event> stream(UUID ticketId) {
        return storedMessages(ticketId).stream()
            .<Event>map(EventMessage::payload)
            .toList();
    }

    private List<EventMessage<Event>> storedMessages(UUID ticketId) {
        return eventStorage.loadSubjectStream(new Subject("test-ticket", ticketId.toString())).stream()
            .map(stored -> eventSerializer.<Event>deserialize(stored.serializedEvent()))
            .toList();
    }

    @Aggregate("test-ticket")
    public static class ObservedTicket {

        @AggregateId
        private UUID ticketId;
        private String lastApplied;

        private ObservedTicket() {
        }

        @AggregateCommandHandler
        public ObservedTicket(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }

        @AggregateCommandHandler
        public String handle(AssignTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketAssignedEvent(command.ticketId(), command.assignee()));
            return lastApplied;
        }

        @AggregateCommandHandler
        public String handle(ObserveTestTicketCommand command) {
            return lastApplied;
        }

        @EventSourcingHandler
        void on(TestTicketOpenedEvent event) {
            ticketId = event.ticketId();
        }

        @EventSourcingHandler
        void on(TestTicketAssignedEvent event, MessageId messageId, Instant timestamp) {
            lastApplied = messageId + "@" + timestamp;
        }
    }

    public static class PlainOpenTestTicketHandler {

        @CommandHandler
        public UUID handle(OpenTestTicketCommand command) {
            return command.ticketId();
        }
    }

    public record ObserveTestTicketCommand(@TargetAggregateId UUID ticketId) implements Command<String> {
    }

    public interface TitleFormatter {

        String format(String value);
    }

    private static class UpperCaseTitleFormatter implements TitleFormatter {

        @Override
        public String format(String value) {
            return value.toUpperCase();
        }
    }

    private record StubObjectCreator(Map<Class<?>, Object> objects) implements ObjectCreator {

        @Override
        public <T> Provider<T> create(Class<T> clazz) {
            var object = objects.get(clazz);
            if (object == null) {
                throw new IllegalArgumentException("No object for " + clazz.getName());
            }
            return new SingletonProvider<>(clazz.cast(object));
        }
    }

    @Aggregate("test-ticket")
    public static class CollaboratingTicket {

        @AggregateId
        private UUID ticketId;
        private String assignee;

        private CollaboratingTicket() {
        }

        @AggregateCommandHandler
        public CollaboratingTicket(OpenTestTicketCommand command, TitleFormatter formatter, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), formatter.format(command.title())));
        }

        @AggregateCommandHandler
        public String handle(AssignTestTicketCommand command, TitleFormatter formatter, AggregateEvents<Event> events) {
            events.apply(new TestTicketAssignedEvent(command.ticketId(), formatter.format(command.assignee())));
            return assignee;
        }

        @EventSourcingHandler
        void on(TestTicketOpenedEvent event) {
            ticketId = event.ticketId();
        }

        @EventSourcingHandler
        void on(TestTicketAssignedEvent event) {
            assignee = event.assignee();
        }
    }

    @Aggregate("test-ticket")
    public static class OrderedTicket {

        @AggregateId
        private UUID ticketId;
        private final List<String> applied = new ArrayList<>();

        private OrderedTicket() {
        }

        @AggregateCommandHandler
        public OrderedTicket(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }

        @AggregateCommandHandler
        public String handle(ObserveTestTicketCommand command) {
            return String.join(", ", applied);
        }

        @EventSourcingHandler(order = 2)
        void on(Event event) {
            applied.add("any");
        }

        @EventSourcingHandler(order = 1)
        void on(TestTicketOpenedEvent event) {
            ticketId = event.ticketId();
            applied.add("opened");
        }
    }

    @Aggregate("same-order-handlers")
    public static class AggregateWithSameOrderHandlers {

        @AggregateId
        private UUID ticketId;

        private AggregateWithSameOrderHandlers() {
        }

        @EventSourcingHandler
        void on(Event event) {
        }

        @EventSourcingHandler
        void on(TestTicketOpenedEvent event) {
            ticketId = event.ticketId();
        }
    }

    public static class UnnamedAggregate {

        @AggregateId
        private UUID id;

        private UnnamedAggregate() {
        }
    }

    @Aggregate("without-aggregate-id")
    public static class AggregateWithoutAggregateId {

        private AggregateWithoutAggregateId() {
        }
    }

    @Aggregate("unsupported-event-sourcing-parameter")
    public static class AggregateWithUnsupportedEventSourcingParameter {

        @AggregateId
        private UUID id;

        private AggregateWithUnsupportedEventSourcingParameter() {
        }

        @EventSourcingHandler
        void on(TestTicketOpenedEvent event, Integer count) {
        }
    }

    @Aggregate("static-event-sourcing-handler")
    public static class AggregateWithStaticEventSourcingHandler {

        @AggregateId
        private UUID id;

        private AggregateWithStaticEventSourcingHandler() {
        }

        @EventSourcingHandler
        static void on(TestTicketOpenedEvent event) {
        }
    }

    @Aggregate("wrong-update-result")
    public static class AggregateWithWrongUpdateResult {

        @AggregateId
        private UUID id;

        private AggregateWithWrongUpdateResult() {
        }

        @AggregateCommandHandler
        public AggregateWithWrongUpdateResult(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }

        @AggregateCommandHandler
        public String handle(ResolveTestTicketCommand command) {
            return command.resolution();
        }

        @EventSourcingHandler
        void on(TestTicketOpenedEvent event) {
            id = event.ticketId();
        }
    }

    @Aggregate("plain-command-handler")
    public static class AggregateWithPlainCommandHandler {

        @AggregateId
        private UUID id;

        private AggregateWithPlainCommandHandler() {
        }

        @CommandHandler
        public UUID handle(ResolveTestTicketCommand command) {
            return command.ticketId();
        }
    }

    @Aggregate("without-creating-constructor")
    public static class AggregateWithoutCreatingConstructor {

        @AggregateId
        private UUID id;

        private AggregateWithoutCreatingConstructor() {
        }

        @AggregateCommandHandler
        public UUID handle(ResolveTestTicketCommand command) {
            return command.ticketId();
        }
    }

    @Aggregate("untargeted")
    public static class AggregateWithUntargetedCommand {

        @AggregateId
        private UUID id;

        private AggregateWithUntargetedCommand() {
        }

        @AggregateCommandHandler
        public AggregateWithUntargetedCommand(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }

        @AggregateCommandHandler
        public UUID handle(ReopenTestTicketCommand command) {
            return command.ticketId();
        }
    }

    @Aggregate("targeted-creation")
    public static class AggregateWithTargetedCreation {

        @AggregateId
        private UUID id;

        private AggregateWithTargetedCreation() {
        }

        @AggregateCommandHandler
        public AggregateWithTargetedCreation(ResolveTestTicketCommand command) {
        }
    }

    @Aggregate("wrong-creation-result")
    public static class AggregateWithWrongCreationResult {

        @AggregateId
        private UUID id;

        private AggregateWithWrongCreationResult() {
        }

        @AggregateCommandHandler
        public AggregateWithWrongCreationResult(DescribeTestTicketCommand command) {
        }
    }

    public record DescribeTestTicketCommand(String title) implements Command<String> {
    }

    @Aggregate("eventless")
    public static class EventlessAggregate {

        @AggregateId
        private UUID id;

        private EventlessAggregate() {
        }

        @AggregateCommandHandler
        public EventlessAggregate(OpenTestTicketCommand command) {
        }
    }

    @Aggregate("unassigned-id")
    public static class AggregateNotAssigningId {

        @AggregateId
        private UUID id;

        private AggregateNotAssigningId() {
        }

        @AggregateCommandHandler
        public AggregateNotAssigningId(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }
    }

    public record ReopenTestTicketCommand(UUID ticketId) implements Command<UUID> {
    }

    @Aggregate("static-updating-handler")
    public static class AggregateWithStaticUpdatingHandler {

        @AggregateId
        private UUID id;

        private AggregateWithStaticUpdatingHandler() {
        }

        @AggregateCommandHandler
        public AggregateWithStaticUpdatingHandler(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }

        @AggregateCommandHandler
        public static String handle(AssignTestTicketCommand command) {
            return command.assignee();
        }
    }

    @Aggregate("mistyped-target-aggregate-id")
    public static class AggregateWithMistypedTargetAggregateId {

        @AggregateId
        private UUID id;

        private AggregateWithMistypedTargetAggregateId() {
        }

        @AggregateCommandHandler
        public AggregateWithMistypedTargetAggregateId(OpenTestTicketCommand command, AggregateEvents<Event> events) {
            events.apply(new TestTicketOpenedEvent(command.ticketId(), command.title()));
        }

        @AggregateCommandHandler
        public UUID handle(ReopenTestTicketByNameCommand command) {
            return id;
        }
    }

    public record ReopenTestTicketByNameCommand(@TargetAggregateId String ticketId) implements Command<UUID> {
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
