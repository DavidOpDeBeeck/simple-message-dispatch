package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.RetryBackoffStrategy;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.storage.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.exponential;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;

public class DurableDeliveryConfig {

    public static final int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    public static final Duration DEFAULT_POLLING_DELAY = ofMillis(200);
    public static final Duration DEFAULT_STALL_WARNING_AFTER = ofMinutes(1);
    public static final RetryBackoffStrategy DEFAULT_RETRY_BACKOFF_STRATEGY = exponential(ofSeconds(1), 2.0, ofMinutes(5));

    private final ConnectionProvider connectionProvider;
    private final TransactionProvider transactionProvider;
    private final EventSerializer eventSerializer;
    private final ScheduledExecutorService scheduler;
    private final Duration pollingDelay;
    private final Duration stallWarningAfter;
    private final RetryBackoffStrategy retryBackoffStrategy;

    private DurableDeliveryConfig(Builder builder) {
        this.connectionProvider = requireNonNull(builder.connectionProvider);
        this.transactionProvider = requireNonNull(builder.transactionProvider);
        this.eventSerializer = requireNonNull(builder.eventSerializer);
        this.scheduler = requireNonNull(builder.scheduler);
        this.pollingDelay = requirePositive("pollingDelay", builder.pollingDelay);
        this.stallWarningAfter = requirePositive("stallWarningAfter", builder.stallWarningAfter);
        this.retryBackoffStrategy = requireNonNull(builder.retryBackoffStrategy);
    }

    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    public TransactionProvider getTransactionProvider() {
        return transactionProvider;
    }

    public EventSerializer getEventSerializer() {
        return eventSerializer;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public Duration getPollingDelay() {
        return pollingDelay;
    }

    public Duration getStallWarningAfter() {
        return stallWarningAfter;
    }

    public RetryBackoffStrategy getRetryBackoffStrategy() {
        return retryBackoffStrategy;
    }

    public static Builder withDefaults() {
        return new Builder()
            .scheduler(newScheduledThreadPool(DEFAULT_THREAD_POOL_SIZE))
            .pollingDelay(DEFAULT_POLLING_DELAY)
            .stallWarningAfter(DEFAULT_STALL_WARNING_AFTER)
            .retryBackoffStrategy(DEFAULT_RETRY_BACKOFF_STRATEGY);
    }

    public static Builder withoutDefaults() {
        return new Builder();
    }

    private static Duration requirePositive(String name, Duration duration) {
        var value = requireNonNull(duration);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Durable delivery " + name + " must be greater than zero; got " + value);
        }
        return value;
    }

    public static class Builder {

        private ConnectionProvider connectionProvider;
        private TransactionProvider transactionProvider;
        private EventSerializer eventSerializer;
        private ScheduledExecutorService scheduler;
        private Duration pollingDelay;
        private Duration stallWarningAfter;
        private RetryBackoffStrategy retryBackoffStrategy;

        private Builder() {
        }

        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = connectionProvider;
            return this;
        }

        public Builder transactionProvider(TransactionProvider transactionProvider) {
            this.transactionProvider = transactionProvider;
            return this;
        }

        public Builder eventSerializer(EventSerializer eventSerializer) {
            this.eventSerializer = eventSerializer;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder pollingDelay(Duration pollingDelay) {
            this.pollingDelay = pollingDelay;
            return this;
        }

        public Builder stallWarningAfter(Duration stallWarningAfter) {
            this.stallWarningAfter = stallWarningAfter;
            return this;
        }

        public Builder retryBackoffStrategy(RetryBackoffStrategy retryBackoffStrategy) {
            this.retryBackoffStrategy = retryBackoffStrategy;
            return this;
        }

        public DurableDeliveryConfig build() {
            return new DurableDeliveryConfig(this);
        }
    }
}
