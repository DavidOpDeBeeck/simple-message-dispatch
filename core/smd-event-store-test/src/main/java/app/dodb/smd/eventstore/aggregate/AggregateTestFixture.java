package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import app.dodb.smd.eventstore.aggregate.AggregateTestResult.Outcome;
import app.dodb.smd.eventstore.aggregate.AggregateTestResult.Outcome.Failed;
import app.dodb.smd.eventstore.aggregate.AggregateTestResult.Outcome.Succeeded;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.test.NoOpTransactionProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

public class AggregateTestFixture<A> {

    private final AggregateModel<A> model;
    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final EventSerializer eventSerializer;
    private final MetadataFactory metadataFactory;
    private final Map<Class<?>, Object> collaborators;
    private final AggregateRepository<A> repository;
    private final CommandBus commandBus;

    private AggregateTestFixture(Builder<A> builder) {
        this.model = AggregateModel.inspect(builder.aggregateClass);
        this.eventSerializer = requireNonNull(builder.eventSerializer);
        this.metadataFactory = new MetadataFactory(requireNonNull(builder.principalProvider), requireNonNull(builder.timeProvider));
        this.collaborators = requireNonNull(builder.collaborators);

        var eventStore = new EventStore(EventStoreConfig.withDefaults()
            .transactionProvider(new NoOpTransactionProvider())
            .eventStorage(eventStorage)
            .eventSerializer(eventSerializer)
            .tokenStore(new InMemoryTokenStore())
            .eventSequenceStore(new InMemoryEventSequenceStore())
            .schedulingConfig(SchedulingConfig.withDefaults().enabled(false).build())
            .build());
        var eventBus = EventBusSpec.withDefaults()
            .sinks(eventStore.inlet())
            .create();
        this.repository = new AggregateRepository<>(model, eventStore, eventBus, metadataFactory);
        this.commandBus = CommandBusSpec.withoutDefaults()
            .time(builder.timeProvider)
            .principal(builder.principalProvider)
            .commandHandlers(() -> AnnotatedAggregateCommandHandler.from(repository, new RegisteredCollaborators()))
            .create();
    }

    public static <A> AggregateTestFixture<A> of(Class<A> aggregateClass) {
        return builder(aggregateClass).build();
    }

    public static <A> Builder<A> builder(Class<A> aggregateClass) {
        return new Builder<>(aggregateClass);
    }

    public AggregateTestFixture<A> givenNoPriorActivity() {
        return this;
    }

    public AggregateTestFixture<A> given(Event... events) {
        var testedAggregateId = testedAggregateId();
        var history = new ArrayList<>(testedAggregateId.map(this::loadStream).orElse(List.of()));
        var loadedVersion = history.size();
        List<EventMessage<?>> givenMessages = Stream.of(events)
            .<EventMessage<?>>map(event -> EventMessage.from(event, metadataFactory.createScope().metadata()))
            .toList();
        history.addAll(givenMessages);

        var givenAggregateId = model.aggregateId(model.replay(history)).orElseThrow(() -> new IllegalArgumentException("""
            Invalid given events: an @EventSourcingHandler must assign the @AggregateId.

                Aggregate:
                %s
            """.formatted(logClass(model.aggregateClass())))).toString();
        testedAggregateId.filter(id -> !id.equals(givenAggregateId)).ifPresent(id -> {
            throw moreThanOneAggregate(List.of(id, givenAggregateId));
        });

        repository.publish(new Subject(model.aggregateType(), givenAggregateId), loadedVersion, givenMessages);
        return this;
    }

    public AggregateTestFixture<A> givenCommands(Command<?>... commands) {
        for (var command : commands) {
            commandBus.send(command);
        }
        return this;
    }

    public <R> AggregateTestResult<A, R> when(Command<R> command) {
        var appendedBefore = eventStorage.events().size();
        Outcome<R> outcome;
        try {
            outcome = new Succeeded<>(commandBus.send(command));
        } catch (Exception e) {
            outcome = new Failed<>(e);
        }

        var stored = eventStorage.events();
        List<Event> events = stored.subList(appendedBefore, stored.size()).stream()
            .map(storedEvent -> (Event) eventSerializer.deserialize(storedEvent.serializedEvent()).payload())
            .toList();
        return new AggregateTestResult<>(events, outcome, state());
    }

    private Supplier<Optional<A>> state() {
        var testedAggregateId = testedAggregateId();
        return () -> testedAggregateId.map(id -> model.replay(loadStream(id)));
    }

    // The fixture's storage holds only the streams that its repository appended, so they identify the tested aggregate.
    private Optional<String> testedAggregateId() {
        var aggregateIds = eventStorage.events().stream()
            .map(StoredEvent::subject)
            .flatMap(Optional::stream)
            .map(Subject::id)
            .distinct()
            .toList();
        if (aggregateIds.size() > 1) {
            throw moreThanOneAggregate(aggregateIds);
        }
        return aggregateIds.stream().findFirst();
    }

    private List<EventMessage<?>> loadStream(String aggregateId) {
        return eventStorage.loadSubjectStream(new Subject(model.aggregateType(), aggregateId)).stream()
            .<EventMessage<?>>map(storedEvent -> eventSerializer.deserialize(storedEvent.serializedEvent()))
            .toList();
    }

    private IllegalArgumentException moreThanOneAggregate(List<String> aggregateIds) {
        return new IllegalArgumentException("""
            Invalid fixture use: the fixture tests one aggregate.

                Aggregate:
                %s

                Aggregate IDs:
                %s
            """.formatted(logClass(model.aggregateClass()), String.join(", ", aggregateIds)));
    }

    private class RegisteredCollaborators implements ObjectCreator {

        @Override
        public <T> Provider<T> create(Class<T> clazz) {
            return new Provider<>() {

                // An AssertionError, so when(...) does not record a missing collaborator as the command's exception.
                @Override
                public T get() {
                    if (!collaborators.containsKey(clazz)) {
                        throw new AssertionError("""
                            Missing aggregate collaborator: register it with collaborator(type, collaborator) on the fixture builder.

                                Type:
                                %s
                            """.formatted(logClass(clazz)));
                    }
                    return clazz.cast(collaborators.get(clazz));
                }

                @Override
                public Class<?> getType() {
                    return clazz;
                }
            };
        }
    }

    public static class Builder<A> {

        private final Class<A> aggregateClass;
        private final Map<Class<?>, Object> collaborators = new HashMap<>();
        private EventSerializer eventSerializer = new PassThroughEventSerializer();
        private TimeProvider timeProvider = new SystemTimeProvider();
        private PrincipalProvider principalProvider = new SimplePrincipalProvider();

        private Builder(Class<A> aggregateClass) {
            this.aggregateClass = requireNonNull(aggregateClass);
        }

        public Builder<A> eventSerializer(EventSerializer eventSerializer) {
            this.eventSerializer = eventSerializer;
            return this;
        }

        public <T> Builder<A> collaborator(Class<T> type, T collaborator) {
            collaborators.put(type, collaborator);
            return this;
        }

        public Builder<A> time(TimeProvider timeProvider) {
            this.timeProvider = timeProvider;
            return this;
        }

        public Builder<A> principal(PrincipalProvider principalProvider) {
            this.principalProvider = principalProvider;
            return this;
        }

        public AggregateTestFixture<A> build() {
            return new AggregateTestFixture<>(this);
        }
    }

    private static class PassThroughEventSerializer implements EventSerializer {

        private final Map<MessageId, EventMessage<?>> eventMessages = new HashMap<>();

        @Override
        public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
            eventMessages.put(eventMessage.messageId(), eventMessage);
            return new SerializedEvent(
                eventMessage.messageId(),
                eventMessage.subject(),
                eventMessage.payload().getClass().getName(),
                new byte[0],
                new byte[0],
                eventMessage.metadata().timestamp()
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
            return (EventMessage<E>) eventMessages.get(serializedEvent.messageId());
        }
    }
}
