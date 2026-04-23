package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.command.AnnotatedCommandHandler;
import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.CommandHandlerRegistry;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.eventstore.store.Cursor;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.SerializedEvent;
import app.dodb.smd.eventstore.store.Token;
import app.dodb.smd.eventstore.store.TokenStore;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventStoreChannelTest {

    private static final Principal PRINCIPAL = SimplePrincipal.create();
    private static final Instant TIMESTAMP = Instant.parse("2026-04-23T10:15:30Z");

    @Test
    void subscribe_pollingContinuesAfterPollingError() {
        var tokenStore = new AlwaysFailingTokenStore();

        try (var channel = new EventStoreChannel(EventStoreChannelConfig.withoutDefaults()
            .transactionProvider(new DirectTransactionProvider())
            .interceptors(List.of())
            .eventStorage(new EmptyEventStorage())
            .eventSerializer(new UnusedEventSerializer())
            .tokenStore(tokenStore)
            .schedulingConfig(EventStoreChannelConfig.SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(Executors.newSingleThreadScheduledExecutor())
                .initialDelay(Duration.ZERO)
                .pollingDelay(Duration.ofMillis(10))
                .build())
            .processingConfig(EventStoreChannelConfig.ProcessingConfig.withoutDefaults()
                .maxRetries(1)
                .batchSize(1)
                .retryBackoffStrategy(fixed(Duration.ZERO))
                .gapTimeout(Duration.ofSeconds(1))
                .build())
            .build())) {
            channel.subscribe(new NoopListener());

            await().untilAsserted(() -> assertThat(tokenStore.calls.get()).isGreaterThanOrEqualTo(2));
        }
    }

    @Test
    void subscribe_nestedCommandFromStoredEventInheritsEventMetadata() {
        var commandHandler = new CommandHandlerForMetadata();
        CommandGateway commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new StaticCommandHandlerLocator(AnnotatedCommandHandler.from(commandHandler)))
            .create();
        var eventMetadata = new Metadata(PRINCIPAL, TIMESTAMP, null, Map.of("key", "value"));
        var eventMessage = EventMessage.from(new EventForTest(), eventMetadata);
        var tokenStore = new SingleTokenStore();

        try (var channel = new EventStoreChannel(EventStoreChannelConfig.withoutDefaults()
            .transactionProvider(new DirectTransactionProvider())
            .interceptors(List.of())
            .eventStorage(new SingleEventStorage())
            .eventSerializer(new FixedEventSerializer(eventMessage))
            .tokenStore(tokenStore)
            .schedulingConfig(EventStoreChannelConfig.SchedulingConfig.withoutDefaults()
                .enabled(true)
                .scheduler(Executors.newSingleThreadScheduledExecutor())
                .initialDelay(Duration.ZERO)
                .pollingDelay(Duration.ofMillis(10))
                .build())
            .processingConfig(EventStoreChannelConfig.ProcessingConfig.withoutDefaults()
                .maxRetries(1)
                .batchSize(1)
                .retryBackoffStrategy(fixed(Duration.ZERO))
                .gapTimeout(Duration.ofSeconds(1))
                .build())
            .build())) {
            channel.subscribe(new NestedCommandDispatchingListener(commandBus));

            await().untilAsserted(() -> {
                var nestedMetadata = commandHandler.handledMetadata.get();
                assertThat(nestedMetadata).isNotNull();
                assertThat(nestedMetadata.principal()).isEqualTo(eventMetadata.principal());
                assertThat(nestedMetadata.properties()).containsEntry("key", "value");
                assertThat(nestedMetadata.parentMessageId()).isEqualTo(eventMessage.messageId());
                assertThat(nestedMetadata.timestamp()).isNotEqualTo(eventMetadata.timestamp());
                assertThat(tokenStore.token.lastProcessedSequenceNumber()).contains(1L);
            });
        }
    }

    private static class AlwaysFailingTokenStore implements TokenStore {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<Token> claimToken(String processingGroup) {
            calls.incrementAndGet();
            throw new IllegalStateException("token store failure");
        }
    }

    private static class EmptyEventStorage implements EventStorage {

        @Override
        public void store(SerializedEvent event) {
        }

        @Override
        public Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit) {
            return new Cursor<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public SerializedEvent next() {
                    throw new IllegalStateException("No events available");
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static class SingleEventStorage implements EventStorage {

        @Override
        public void store(SerializedEvent event) {
        }

        @Override
        public Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit) {
            if (lastProcessedSequenceNumber >= 1) {
                return new EmptyCursor();
            }
            return new Cursor<>() {
                private boolean consumed;

                @Override
                public boolean hasNext() {
                    return !consumed;
                }

                @Override
                public SerializedEvent next() {
                    consumed = true;
                    return new SerializedEvent(
                        app.dodb.smd.api.message.MessageId.generate(),
                        1L,
                        EventForTest.class.getName(),
                        new byte[0],
                        new byte[0],
                        Instant.now()
                    );
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static class EmptyCursor implements Cursor<SerializedEvent> {

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public SerializedEvent next() {
            throw new IllegalStateException("No events available");
        }

        @Override
        public void close() {
        }
    }

    private static class UnusedEventSerializer implements EventSerializer {

        @Override
        public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FixedEventSerializer implements EventSerializer {

        private final EventMessage<?> eventMessage;

        private FixedEventSerializer(EventMessage<?> eventMessage) {
            this.eventMessage = eventMessage;
        }

        @Override
        public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
            return (EventMessage<E>) eventMessage;
        }
    }

    private static class NoopListener implements EventChannelListener {

        @Override
        public String processingGroup() {
            return "processing-group";
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
        }
    }

    private static class NestedCommandDispatchingListener implements EventChannelListener {

        private final CommandGateway commandBus;

        private NestedCommandDispatchingListener(CommandGateway commandBus) {
            this.commandBus = commandBus;
        }

        @Override
        public String processingGroup() {
            return "processing-group";
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            commandBus.send(new CommandForMetadata());
        }
    }

    public record EventForTest() implements Event {
    }

    public record CommandForMetadata() implements Command<Metadata> {
    }

    public static class CommandHandlerForMetadata {

        private final AtomicReference<Metadata> handledMetadata = new AtomicReference<>();

        @CommandHandler
        public Metadata handle(CommandForMetadata command, Metadata metadata, Principal principal) {
            handledMetadata.set(metadata);
            return metadata;
        }
    }

    private record StaticCommandHandlerLocator(CommandHandlerRegistry registry) implements CommandHandlerLocator {

        @Override
        public CommandHandlerRegistry locate() {
            return registry;
        }
    }

    private static class SingleTokenStore implements TokenStore {

        private final MutableToken token = new MutableToken();

        @Override
        public Optional<Token> claimToken(String processingGroup) {
            return Optional.of(token);
        }
    }

    private static class MutableToken implements Token {

        private Long lastProcessedSequenceNumber;

        @Override
        public Optional<Long> lastProcessedSequenceNumber() {
            return Optional.ofNullable(lastProcessedSequenceNumber);
        }

        @Override
        public int errorCount() {
            return 0;
        }

        @Override
        public Instant lastErrorAt() {
            return null;
        }

        @Override
        public Instant lastGapDetectedAt() {
            return null;
        }

        @Override
        public void markProcessed(Long sequenceNumber) {
            this.lastProcessedSequenceNumber = sequenceNumber;
        }

        @Override
        public void markFailed(Long sequenceNumber, Exception exception) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAbandoned(Long sequenceNumber, Exception exception) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markGapDetected(Long gapSequenceNumber) {
            throw new UnsupportedOperationException();
        }
    }

    private static class DirectTransactionProvider implements TransactionProvider {

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
