package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.eventstore.RetryBackoffStrategy;
import app.dodb.smd.eventstore.serialization.DeadlineSerializer;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.exponential;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class DeadlineStoreConfig {

    public static final Duration DEFAULT_INITIAL_DELAY = ofSeconds(10);
    public static final Duration DEFAULT_POLLING_DELAY = ofSeconds(5);
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final RetryBackoffStrategy DEFAULT_RETRY_BACKOFF_STRATEGY = exponential(ofSeconds(1), 5.0, ofMinutes(5));

    private final TransactionProvider transactionProvider;
    private final PrincipalProvider principalProvider;
    private final TimeProvider timeProvider;
    private final DeadlineStorage deadlineStorage;
    private final DeadlineSerializer deadlineSerializer;
    private final ScheduledExecutorService scheduler;
    private final Duration initialDelay;
    private final Duration pollingDelay;
    private final int batchSize;
    private final int maxRetries;
    private final RetryBackoffStrategy retryBackoffStrategy;

    private DeadlineStoreConfig(Builder builder) {
        this.transactionProvider = requireNonNull(builder.transactionProvider);
        this.principalProvider = requireNonNull(builder.principalProvider);
        this.timeProvider = requireNonNull(builder.timeProvider);
        this.deadlineStorage = requireNonNull(builder.deadlineStorage);
        this.deadlineSerializer = requireNonNull(builder.deadlineSerializer);
        this.scheduler = requireNonNull(builder.scheduler);
        this.initialDelay = requireAtLeastZero("initialDelay", builder.initialDelay);
        this.pollingDelay = requireGreaterThanZero("pollingDelay", builder.pollingDelay);
        this.batchSize = requireGreaterThanZero("batchSize", builder.batchSize);
        this.maxRetries = requireAtLeastZero("maxRetries", builder.maxRetries);
        this.retryBackoffStrategy = requireNonNull(builder.retryBackoffStrategy);
    }

    public TransactionProvider getTransactionProvider() {
        return transactionProvider;
    }

    public PrincipalProvider getPrincipalProvider() {
        return principalProvider;
    }

    public TimeProvider getTimeProvider() {
        return timeProvider;
    }

    public DeadlineStorage getDeadlineStorage() {
        return deadlineStorage;
    }

    public DeadlineSerializer getDeadlineSerializer() {
        return deadlineSerializer;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public Duration getPollingDelay() {
        return pollingDelay;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public RetryBackoffStrategy getRetryBackoffStrategy() {
        return retryBackoffStrategy;
    }

    public static Builder withDefaults() {
        return new Builder()
            .scheduler(newSingleThreadScheduledExecutor(Thread.ofPlatform().name("smd-deadlines-", 1).factory()))
            .initialDelay(DEFAULT_INITIAL_DELAY)
            .pollingDelay(DEFAULT_POLLING_DELAY)
            .batchSize(DEFAULT_BATCH_SIZE)
            .maxRetries(DEFAULT_MAX_RETRIES)
            .retryBackoffStrategy(DEFAULT_RETRY_BACKOFF_STRATEGY);
    }

    public static Builder withoutDefaults() {
        return new Builder();
    }

    public static class Builder {

        private TransactionProvider transactionProvider;
        private PrincipalProvider principalProvider;
        private TimeProvider timeProvider;
        private DeadlineStorage deadlineStorage;
        private DeadlineSerializer deadlineSerializer;
        private ScheduledExecutorService scheduler;
        private Duration initialDelay;
        private Duration pollingDelay;
        private Integer batchSize;
        private Integer maxRetries;
        private RetryBackoffStrategy retryBackoffStrategy;

        private Builder() {
        }

        public Builder transactionProvider(TransactionProvider transactionProvider) {
            this.transactionProvider = transactionProvider;
            return this;
        }

        public Builder principalProvider(PrincipalProvider principalProvider) {
            this.principalProvider = principalProvider;
            return this;
        }

        public Builder timeProvider(TimeProvider timeProvider) {
            this.timeProvider = timeProvider;
            return this;
        }

        public Builder deadlineStorage(DeadlineStorage deadlineStorage) {
            this.deadlineStorage = deadlineStorage;
            return this;
        }

        public Builder deadlineSerializer(DeadlineSerializer deadlineSerializer) {
            this.deadlineSerializer = deadlineSerializer;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder pollingDelay(Duration pollingDelay) {
            this.pollingDelay = pollingDelay;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryBackoffStrategy(RetryBackoffStrategy retryBackoffStrategy) {
            this.retryBackoffStrategy = retryBackoffStrategy;
            return this;
        }

        public DeadlineStoreConfig build() {
            return new DeadlineStoreConfig(this);
        }
    }

    private static Duration requireAtLeastZero(String name, Duration duration) {
        var value = requireNonNull(duration);
        if (value.isNegative()) {
            throw new IllegalArgumentException("Deadline store " + name + " must not be negative: " + value);
        }
        return value;
    }

    private static Duration requireGreaterThanZero(String name, Duration duration) {
        var value = requireNonNull(duration);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Deadline store " + name + " must be greater than zero: " + value);
        }
        return value;
    }

    private static int requireAtLeastZero(String name, Integer value) {
        int result = requireNonNull(value);
        if (result < 0) {
            throw new IllegalArgumentException("Deadline store " + name + " must not be negative: " + result);
        }
        return result;
    }

    private static int requireGreaterThanZero(String name, Integer value) {
        int result = requireNonNull(value);
        if (result <= 0) {
            throw new IllegalArgumentException("Deadline store " + name + " must be greater than zero: " + result);
        }
        return result;
    }
}
