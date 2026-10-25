package app.dodb.smd.eventstore;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventSequenceStore;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryEventStorage;
import app.dodb.smd.eventstore.storage.inmemory.InMemoryTokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig.DEFAULT_INITIAL_DELAY;
import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig.DEFAULT_POLLING_DELAY;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public final class AggregateTestFixture<A> {

    private final AggregateModel<A> model;
    private final InMemoryEventStorage eventStorage = new InMemoryEventStorage();
    private final StoredEventSerializer storedEventSerializer;
    private final CommandBus commandBus;
    private Optional<String> aggregateId = Optional.empty();

    private AggregateTestFixture(Class<A> aggregateClass, EventSerializer eventSerializer) {
        this.model = AggregateModel.inspect(aggregateClass);
        this.storedEventSerializer = new StoredEventSerializer(eventSerializer);

        var config = EventStoreConfig.withoutDefaults()
            .transactionProvider(new ImmediateTransactionProvider())
            .eventStorage(eventStorage)
            .eventSerializer(eventSerializer)
            .tokenStore(new InMemoryTokenStore())
            .eventSequenceStore(new InMemoryEventSequenceStore())
            .schedulingConfig(SchedulingConfig.withoutDefaults()
                .enabled(false)
                .scheduler(newSingleThreadScheduledExecutor())
                .initialDelay(DEFAULT_INITIAL_DELAY)
                .pollingDelay(DEFAULT_POLLING_DELAY)
                .build())
            .processingConfig(ProcessingConfig.withDefaults().build())
            .build();
        var eventBus = EventBusSpec.withDefaults()
            .sinks(new EventStoreSink(config))
            .create();
        this.commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new AggregateCommandHandlerLocator(List.of(aggregateClass), config, eventBus))
            .create();
    }

    public static <A> AggregateTestFixture<A> of(Class<A> aggregateClass) {
        return new AggregateTestFixture<>(aggregateClass, DefaultEventSerializer.create());
    }

    public static <A> AggregateTestFixture<A> of(Class<A> aggregateClass, EventSerializer eventSerializer) {
        return new AggregateTestFixture<>(aggregateClass, requireNonNull(eventSerializer));
    }

    public AggregateTestFixture<A> givenNoPriorActivity() {
        return this;
    }

    public AggregateTestFixture<A> given(Event... events) {
        var history = new ArrayList<>(aggregateId.map(this::loadStream).orElse(List.of()));
        var loadedVersion = history.size();
        history.addAll(List.of(events));

        var givenAggregateId = model.aggregateId(replay(history)).orElseThrow(() -> new IllegalArgumentException("""
            Invalid given events: an @EventSourcingHandler must assign the @AggregateId.

                Aggregate:
                %s
            """.formatted(logClass(model.aggregateClass()))));
        aggregateId.filter(id -> !id.equals(givenAggregateId)).ifPresent(id -> {
            throw new IllegalArgumentException("""
                Invalid given events: the fixture tests one aggregate.

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(id, givenAggregateId));
        });

        var version = (long) loadedVersion;
        for (var event : events) {
            var revision = new AggregateRevision(model.aggregateType(), givenAggregateId, ++version);
            var eventMessage = EventMessage.from(event, new Metadata(null, Instant.now(), null));
            eventStorage.append(storedEventSerializer.serialize(eventMessage, Optional.of(revision)), revision);
        }
        aggregateId = Optional.of(givenAggregateId);
        return this;
    }

    public AggregateTestFixture<A> givenCommands(Command<?>... commands) {
        for (var command : commands) {
            var appendedBefore = eventStorage.events().size();
            commandBus.send(command);
            rememberAggregate(appendedSince(appendedBefore));
        }
        return this;
    }

    public <R> AggregateTestResult<A, R> when(Command<R> command) {
        var appendedBefore = eventStorage.events().size();
        R result = null;
        Exception exception = null;
        try {
            result = commandBus.send(command);
        } catch (Exception e) {
            exception = e;
        }

        var appended = appendedSince(appendedBefore);
        rememberAggregate(appended);
        List<Event> events = appended.stream()
            .map(storedEvent -> (Event) storedEventSerializer.deserialize(storedEvent).payload())
            .toList();
        return new AggregateTestResult<>(events, result, Optional.ofNullable(exception), state());
    }

    private Supplier<Optional<A>> state() {
        var testedAggregateId = aggregateId;
        return () -> testedAggregateId.map(id -> replay(loadStream(id)));
    }

    private List<StoredEvent> appendedSince(int appendedBefore) {
        var events = eventStorage.events();
        return events.subList(appendedBefore, events.size());
    }

    private void rememberAggregate(List<StoredEvent> appended) {
        appended.stream()
            .map(StoredEvent::aggregateId)
            .flatMap(Optional::stream)
            .findFirst()
            .ifPresent(id -> aggregateId = Optional.of(id));
    }

    private List<Event> loadStream(String aggregateId) {
        return eventStorage.loadAggregateStream(model.aggregateType(), aggregateId).stream()
            .map(storedEvent -> (Event) storedEventSerializer.deserialize(storedEvent).payload())
            .toList();
    }

    private A replay(List<Event> history) {
        var instance = model.newInstance();
        history.forEach(event -> model.applyEvent(instance, event));
        return instance;
    }

    // Loaded only when the default is used, so Jackson stays optional for fixtures given their own serializer.
    private static final class DefaultEventSerializer {

        private static EventSerializer create() {
            return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
        }
    }

    private static final class ImmediateTransactionProvider implements TransactionProvider {

        @Override
        public void defer(Runnable runnable) {
            runnable.run();
        }

        @Override
        public <T> T doInTransaction(Supplier<T> supplier) {
            return supplier.get();
        }

        @Override
        public void doInTransaction(Runnable runnable) {
            runnable.run();
        }

        @Override
        public <T> T doInNewTransaction(Supplier<T> supplier) {
            return supplier.get();
        }

        @Override
        public void doInNewTransaction(Runnable runnable) {
            runnable.run();
        }

        @Override
        public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
            return supplier.get();
        }
    }
}
