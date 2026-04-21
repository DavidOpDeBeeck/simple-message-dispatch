package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.store.Cursor;
import app.dodb.smd.eventstore.store.EventSerializer;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.SerializedEvent;
import app.dodb.smd.eventstore.store.Token;
import app.dodb.smd.eventstore.store.TokenStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventStoreChannelTest {

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

    private static class AlwaysFailingTokenStore implements TokenStore {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Token getToken(String processingGroup) {
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

    private static class NoopListener implements EventChannelListener {

        @Override
        public String processingGroup() {
            return "processing-group";
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
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
