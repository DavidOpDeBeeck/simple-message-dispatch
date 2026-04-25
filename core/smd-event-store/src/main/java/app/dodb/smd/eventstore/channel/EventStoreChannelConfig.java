package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.TokenStore;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.exponential;
import static app.dodb.smd.eventstore.utils.ValidationUtils.requireAtLeastZero;
import static app.dodb.smd.eventstore.utils.ValidationUtils.requireGreaterThanZero;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;

public class EventStoreChannelConfig {

    private final TransactionProvider transactionProvider;
    private final List<EventInterceptor> interceptors;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;
    private final TokenStore tokenStore;
    private final SchedulingConfig schedulingConfig;
    private final ProcessingConfig processingConfig;

    private EventStoreChannelConfig(Builder builder) {
        this.transactionProvider = requireNonNull(builder.transactionProvider);
        this.interceptors = requireNonNull(builder.interceptors);
        this.eventStorage = requireNonNull(builder.eventStorage);
        this.eventSerializer = requireNonNull(builder.eventSerializer);
        this.tokenStore = requireNonNull(builder.tokenStore);
        this.schedulingConfig = requireNonNull(builder.schedulingConfig);
        this.processingConfig = requireNonNull(builder.processingConfig);
    }

    public TransactionProvider getTransactionProvider() {
        return transactionProvider;
    }

    public List<EventInterceptor> getInterceptors() {
        return interceptors;
    }

    public EventStorage getEventStorage() {
        return eventStorage;
    }

    public EventSerializer getEventSerializer() {
        return eventSerializer;
    }

    public TokenStore getTokenStore() {
        return tokenStore;
    }

    public SchedulingConfig getSchedulingConfig() {
        return schedulingConfig;
    }

    public ProcessingConfig getProcessingConfig() {
        return processingConfig;
    }

    public static Builder withDefaults() {
        return new Builder()
            .interceptors(List.of())
            .schedulingConfig(SchedulingConfig.withDefaults().build())
            .processingConfig(ProcessingConfig.withDefaults().build());
    }

    public static Builder withoutDefaults() {
        return new Builder();
    }

    public static class Builder {

        private TransactionProvider transactionProvider;
        private List<EventInterceptor> interceptors;
        private TokenStore tokenStore;
        private EventStorage eventStorage;
        private EventSerializer eventSerializer;
        private SchedulingConfig schedulingConfig;
        private ProcessingConfig processingConfig;

        private Builder() {
        }

        public Builder transactionProvider(TransactionProvider transactionProvider) {
            this.transactionProvider = transactionProvider;
            return this;
        }

        public Builder interceptors(List<EventInterceptor> interceptors) {
            this.interceptors = interceptors;
            return this;
        }

        public Builder tokenStore(TokenStore tokenStore) {
            this.tokenStore = tokenStore;
            return this;
        }

        public Builder eventStorage(EventStorage eventStorage) {
            this.eventStorage = eventStorage;
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

        public EventStoreChannelConfig build() {
            return new EventStoreChannelConfig(this);
        }
    }

    public static class SchedulingConfig {

        public static final boolean DEFAULT_ENABLED = true;
        public static final int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
        public static final Duration DEFAULT_INITIAL_DELAY = ofSeconds(10);
        public static final Duration DEFAULT_POLLING_DELAY = ofSeconds(5);

        private final boolean enabled;
        private final ScheduledExecutorService scheduler;
        private final Duration initialDelay;
        private final Duration pollingDelay;

        private SchedulingConfig(SchedulingConfig.Builder builder) {
            this.enabled = requireNonNull(builder.enabled);
            this.scheduler = requireNonNull(builder.scheduler);
            this.initialDelay = requireAtLeastZero(builder.initialDelay);
            this.pollingDelay = requireGreaterThanZero(builder.pollingDelay);
        }

        public boolean isEnabled() {
            return enabled;
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

        public static Builder withoutDefaults() {
            return new Builder();
        }

        public static Builder withDefaults() {
            return new Builder()
                .enabled(DEFAULT_ENABLED)
                .scheduler(newScheduledThreadPool(DEFAULT_THREAD_POOL_SIZE))
                .initialDelay(DEFAULT_INITIAL_DELAY)
                .pollingDelay(DEFAULT_POLLING_DELAY);
        }

        public static class Builder {

            private Boolean enabled;
            private ScheduledExecutorService scheduler;
            private Duration initialDelay;
            private Duration pollingDelay;

            private Builder() {
            }

            public Builder enabled(boolean enabled) {
                this.enabled = enabled;
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

            public SchedulingConfig build() {
                return new SchedulingConfig(this);
            }
        }
    }

    public static class ProcessingConfig {

        public static final int DEFAULT_MAX_RETRIES = 3;
        public static final int DEFAULT_BATCH_SIZE = 100;
        public static final Duration DEFAULT_GAP_TIMEOUT = ofMinutes(5);
        public static final Duration DEFAULT_RETRY_BASE_DELAY = ofSeconds(1);
        public static final Duration DEFAULT_RETRY_FIXED_DELAY = DEFAULT_RETRY_BASE_DELAY;
        public static final Duration DEFAULT_RETRY_INCREMENT = ofSeconds(5);
        public static final double DEFAULT_RETRY_MULTIPLIER = 5.0;
        public static final Duration DEFAULT_RETRY_MAX_DELAY = ofMinutes(5);

        private static final RetryBackoffStrategy DEFAULT_RETRY_BACKOFF_STRATEGY =
            exponential(DEFAULT_RETRY_BASE_DELAY, DEFAULT_RETRY_MULTIPLIER, DEFAULT_RETRY_MAX_DELAY);

        private final int maxRetries;
        private final int batchSize;
        private final RetryBackoffStrategy retryBackoffStrategy;
        private final Duration gapTimeout;

        private ProcessingConfig(ProcessingConfig.Builder builder) {
            this.maxRetries = requireAtLeastZero(builder.maxRetries);
            this.batchSize = requireGreaterThanZero(builder.batchSize);
            this.retryBackoffStrategy = requireNonNull(builder.retryBackoffStrategy);
            this.gapTimeout = requireAtLeastZero(builder.gapTimeout);
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public RetryBackoffStrategy getRetryBackoffStrategy() {
            return retryBackoffStrategy;
        }

        public Duration getGapTimeout() {
            return gapTimeout;
        }

        public static Builder withoutDefaults() {
            return new Builder();
        }

        public static Builder withDefaults() {
            return new Builder()
                .maxRetries(DEFAULT_MAX_RETRIES)
                .batchSize(DEFAULT_BATCH_SIZE)
                .retryBackoffStrategy(DEFAULT_RETRY_BACKOFF_STRATEGY)
                .gapTimeout(DEFAULT_GAP_TIMEOUT);
        }

        public static class Builder {

            private Integer maxRetries;
            private Integer batchSize;
            private RetryBackoffStrategy retryBackoffStrategy;
            private Duration gapTimeout;

            private Builder() {
            }

            public Builder maxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
                return this;
            }

            public Builder batchSize(int batchSize) {
                this.batchSize = batchSize;
                return this;
            }

            public Builder retryBackoffStrategy(RetryBackoffStrategy retryBackoffStrategy) {
                this.retryBackoffStrategy = retryBackoffStrategy;
                return this;
            }

            public Builder gapTimeout(Duration gapTimeout) {
                this.gapTimeout = gapTimeout;
                return this;
            }

            public ProcessingConfig build() {
                return new ProcessingConfig(this);
            }
        }
    }
}
