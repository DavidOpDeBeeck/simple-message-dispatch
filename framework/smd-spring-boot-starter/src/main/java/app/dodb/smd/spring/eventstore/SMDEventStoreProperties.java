package app.dodb.smd.spring.eventstore;

import app.dodb.smd.eventstore.RetryBackoffStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_BATCH_SIZE;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_GAP_TIMEOUT;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_MAX_RETRIES;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_RETRY_BASE_DELAY;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_RETRY_FIXED_DELAY;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_RETRY_INCREMENT;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_RETRY_MAX_DELAY;
import static app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig.DEFAULT_RETRY_MULTIPLIER;
import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig.DEFAULT_ENABLED;
import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig.DEFAULT_INITIAL_DELAY;
import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig.DEFAULT_POLLING_DELAY;
import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig.DEFAULT_THREAD_POOL_SIZE;
import static app.dodb.smd.eventstore.RetryBackoffStrategy.exponential;
import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.RetryBackoffStrategy.linear;
import static app.dodb.smd.spring.eventstore.SMDEventStoreProperties.ProcessingProperties.Strategy.*;

@ConfigurationProperties(prefix = "smd.event-store")
public class SMDEventStoreProperties {

    private boolean enabled = false;
    private SchedulingProperties scheduling = new SchedulingProperties();
    private ProcessingProperties processing = new ProcessingProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SchedulingProperties getScheduling() {
        return scheduling;
    }

    public void setScheduling(SchedulingProperties scheduling) {
        this.scheduling = scheduling;
    }

    public ProcessingProperties getProcessing() {
        return processing;
    }

    public void setProcessing(ProcessingProperties processing) {
        this.processing = processing;
    }

    public static class SchedulingProperties {

        private boolean enabled = DEFAULT_ENABLED;
        private Duration initialDelay = DEFAULT_INITIAL_DELAY;
        private Duration pollingDelay = DEFAULT_POLLING_DELAY;
        private int threadPoolSize = DEFAULT_THREAD_POOL_SIZE;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getPollingDelay() {
            return pollingDelay;
        }

        public void setPollingDelay(Duration pollingDelay) {
            this.pollingDelay = pollingDelay;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }
    }

    public static class ProcessingProperties {

        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private Duration gapTimeout = DEFAULT_GAP_TIMEOUT;
        private RetryBackoffProperties retryBackoff = new RetryBackoffProperties();

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getGapTimeout() {
            return gapTimeout;
        }

        public void setGapTimeout(Duration gapTimeout) {
            this.gapTimeout = gapTimeout;
        }

        public RetryBackoffProperties getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(RetryBackoffProperties retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public static class RetryBackoffProperties {

            private Strategy strategy = EXPONENTIAL;
            private Duration fixedDelay = DEFAULT_RETRY_FIXED_DELAY;
            private Duration baseDelay = DEFAULT_RETRY_BASE_DELAY;
            private double multiplier = DEFAULT_RETRY_MULTIPLIER;
            private Duration increment = DEFAULT_RETRY_INCREMENT;
            private Duration maxDelay = DEFAULT_RETRY_MAX_DELAY;

            public Strategy getStrategy() {
                return strategy;
            }

            public void setStrategy(Strategy strategy) {
                this.strategy = strategy;
            }

            public Duration getFixedDelay() {
                return fixedDelay;
            }

            public void setFixedDelay(Duration fixedDelay) {
                this.fixedDelay = fixedDelay;
            }

            public Duration getBaseDelay() {
                return baseDelay;
            }

            public void setBaseDelay(Duration baseDelay) {
                this.baseDelay = baseDelay;
            }

            public double getMultiplier() {
                return multiplier;
            }

            public void setMultiplier(double multiplier) {
                this.multiplier = multiplier;
            }

            public Duration getIncrement() {
                return increment;
            }

            public void setIncrement(Duration increment) {
                this.increment = increment;
            }

            public Duration getMaxDelay() {
                return maxDelay;
            }

            public void setMaxDelay(Duration maxDelay) {
                this.maxDelay = maxDelay;
            }

            public RetryBackoffStrategy createStrategy() {
                return switch (strategy) {
                    case FIXED -> fixed(fixedDelay);
                    case LINEAR -> linear(baseDelay, increment, maxDelay);
                    case EXPONENTIAL -> exponential(baseDelay, multiplier, maxDelay);
                };
            }
        }

        public enum Strategy {
            FIXED,
            EXPONENTIAL,
            LINEAR
        }
    }
}
