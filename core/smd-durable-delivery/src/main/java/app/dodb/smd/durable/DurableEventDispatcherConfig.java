package app.dodb.smd.durable;

import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.serialization.EventSerializer;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.exponential;
import static app.dodb.smd.api.utils.ValidationUtils.requirePositive;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;

public class DurableEventDispatcherConfig {

    private final DispatcherStorage dispatcherStorage;
    private final TransactionProvider transactionProvider;
    private final EventSerializer eventSerializer;
    private final SchedulingConfig schedulingConfig;
    private final ProcessingConfig processingConfig;

    private DurableEventDispatcherConfig(Builder builder) {
        this.dispatcherStorage = requireNonNull(builder.dispatcherStorage);
        this.transactionProvider = requireNonNull(builder.transactionProvider);
        this.eventSerializer = requireNonNull(builder.eventSerializer);
        this.schedulingConfig = requireNonNull(builder.schedulingConfig);
        this.processingConfig = requireNonNull(builder.processingConfig);
    }

    public DispatcherStorage getDispatcherStorage() {
        return dispatcherStorage;
    }

    public TransactionProvider getTransactionProvider() {
        return transactionProvider;
    }

    public EventSerializer getEventSerializer() {
        return eventSerializer;
    }

    public SchedulingConfig getSchedulingConfig() {
        return schedulingConfig;
    }

    public ProcessingConfig getProcessingConfig() {
        return processingConfig;
    }

    public static Builder withDefaults() {
        return new Builder()
            .processingConfig(ProcessingConfig.withDefaults().build());
    }

    public static Builder withoutDefaults() {
        return new Builder();
    }

    public static class Builder {

        private DispatcherStorage dispatcherStorage;
        private TransactionProvider transactionProvider;
        private EventSerializer eventSerializer;
        private SchedulingConfig schedulingConfig;
        private ProcessingConfig processingConfig;

        private Builder() {
        }

        public Builder dispatcherStorage(DispatcherStorage dispatcherStorage) {
            this.dispatcherStorage = dispatcherStorage;
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

        public Builder schedulingConfig(SchedulingConfig schedulingConfig) {
            this.schedulingConfig = schedulingConfig;
            return this;
        }

        public Builder processingConfig(ProcessingConfig processingConfig) {
            this.processingConfig = processingConfig;
            return this;
        }

        public DurableEventDispatcherConfig build() {
            return new DurableEventDispatcherConfig(this);
        }
    }

    public static class SchedulingConfig {

        public static final Duration DEFAULT_IDLE_BACKOFF_BASE_DELAY = ofMillis(200);
        public static final Duration DEFAULT_IDLE_BACKOFF_FIXED_DELAY = ofSeconds(1);
        public static final Duration DEFAULT_IDLE_BACKOFF_INCREMENT = ofSeconds(1);
        public static final double DEFAULT_IDLE_BACKOFF_MULTIPLIER = 2.0;
        public static final Duration DEFAULT_IDLE_BACKOFF_MAX_DELAY = ofSeconds(5);

        private static final RetryBackoffStrategy DEFAULT_IDLE_BACKOFF_STRATEGY =
            exponential(DEFAULT_IDLE_BACKOFF_BASE_DELAY, DEFAULT_IDLE_BACKOFF_MULTIPLIER, DEFAULT_IDLE_BACKOFF_MAX_DELAY);

        private final ScheduledExecutorService scheduler;
        private final RetryBackoffStrategy idleBackoffStrategy;

        private SchedulingConfig(SchedulingConfig.Builder builder) {
            this.scheduler = requireNonNull(builder.scheduler);
            this.idleBackoffStrategy = requireNonNull(builder.idleBackoffStrategy);
        }

        public ScheduledExecutorService getScheduler() {
            return scheduler;
        }

        public RetryBackoffStrategy getIdleBackoffStrategy() {
            return idleBackoffStrategy;
        }

        public static Builder withoutDefaults() {
            return new Builder();
        }

        public static Builder withDefaults() {
            return new Builder()
                .idleBackoffStrategy(DEFAULT_IDLE_BACKOFF_STRATEGY);
        }

        public static class Builder {

            private ScheduledExecutorService scheduler;
            private RetryBackoffStrategy idleBackoffStrategy;

            private Builder() {
            }

            public Builder scheduler(ScheduledExecutorService scheduler) {
                this.scheduler = scheduler;
                return this;
            }

            public Builder idleBackoffStrategy(RetryBackoffStrategy idleBackoffStrategy) {
                this.idleBackoffStrategy = idleBackoffStrategy;
                return this;
            }

            public SchedulingConfig build() {
                return new SchedulingConfig(this);
            }
        }
    }

    public static class ProcessingConfig {

        public static final int DEFAULT_BATCH_SIZE = 10;
        public static final int DEFAULT_LANE_COUNT = 16;
        public static final Duration DEFAULT_RETRY_BASE_DELAY = ofSeconds(1);
        public static final Duration DEFAULT_RETRY_FIXED_DELAY = DEFAULT_RETRY_BASE_DELAY;
        public static final Duration DEFAULT_RETRY_INCREMENT = ofSeconds(5);
        public static final double DEFAULT_RETRY_MULTIPLIER = 2.0;
        public static final Duration DEFAULT_RETRY_MAX_DELAY = ofMinutes(5);

        private static final RetryBackoffStrategy DEFAULT_RETRY_BACKOFF_STRATEGY =
            exponential(DEFAULT_RETRY_BASE_DELAY, DEFAULT_RETRY_MULTIPLIER, DEFAULT_RETRY_MAX_DELAY);

        private final int batchSize;
        private final int laneCount;
        private final RetryBackoffStrategy retryBackoffStrategy;

        private ProcessingConfig(ProcessingConfig.Builder builder) {
            this.batchSize = requirePositive("Durable event dispatcher batchSize", builder.batchSize);
            this.laneCount = requirePositive("Durable event dispatcher laneCount", builder.laneCount);
            this.retryBackoffStrategy = requireNonNull(builder.retryBackoffStrategy);
        }

        public int getBatchSize() {
            return batchSize;
        }

        public int getLaneCount() {
            return laneCount;
        }

        public RetryBackoffStrategy getRetryBackoffStrategy() {
            return retryBackoffStrategy;
        }

        public static Builder withoutDefaults() {
            return new Builder();
        }

        public static Builder withDefaults() {
            return new Builder()
                .batchSize(DEFAULT_BATCH_SIZE)
                .laneCount(DEFAULT_LANE_COUNT)
                .retryBackoffStrategy(DEFAULT_RETRY_BACKOFF_STRATEGY);
        }

        public static class Builder {

            private Integer batchSize;
            private Integer laneCount;
            private RetryBackoffStrategy retryBackoffStrategy;

            private Builder() {
            }

            public Builder batchSize(int batchSize) {
                this.batchSize = batchSize;
                return this;
            }

            public Builder laneCount(int laneCount) {
                this.laneCount = laneCount;
                return this;
            }

            public Builder retryBackoffStrategy(RetryBackoffStrategy retryBackoffStrategy) {
                this.retryBackoffStrategy = retryBackoffStrategy;
                return this;
            }

            public ProcessingConfig build() {
                return new ProcessingConfig(this);
            }
        }
    }
}
