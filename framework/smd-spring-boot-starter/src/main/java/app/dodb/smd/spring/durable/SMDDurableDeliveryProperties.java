package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.exponential;
import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.fixed;
import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.linear;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_BATCH_SIZE;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_LANE_COUNT;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_RETRY_BASE_DELAY;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_RETRY_FIXED_DELAY;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_RETRY_INCREMENT;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_RETRY_MAX_DELAY;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.ProcessingConfig.DEFAULT_RETRY_MULTIPLIER;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig.DEFAULT_IDLE_BACKOFF_BASE_DELAY;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig.DEFAULT_IDLE_BACKOFF_FIXED_DELAY;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig.DEFAULT_IDLE_BACKOFF_INCREMENT;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig.DEFAULT_IDLE_BACKOFF_MAX_DELAY;
import static app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig.DEFAULT_IDLE_BACKOFF_MULTIPLIER;
import static app.dodb.smd.spring.durable.SMDDurableDeliveryProperties.Strategy.EXPONENTIAL;

@ConfigurationProperties(prefix = "smd.durable-delivery")
public class SMDDurableDeliveryProperties {

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

        private int threadPoolSize = Runtime.getRuntime().availableProcessors();
        private RetryBackoffProperties idleBackoff = RetryBackoffProperties.withDefaults(
            DEFAULT_IDLE_BACKOFF_FIXED_DELAY,
            DEFAULT_IDLE_BACKOFF_BASE_DELAY,
            DEFAULT_IDLE_BACKOFF_MULTIPLIER,
            DEFAULT_IDLE_BACKOFF_INCREMENT,
            DEFAULT_IDLE_BACKOFF_MAX_DELAY);

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }

        public RetryBackoffProperties getIdleBackoff() {
            return idleBackoff;
        }

        public void setIdleBackoff(RetryBackoffProperties idleBackoff) {
            this.idleBackoff = idleBackoff;
        }
    }

    public static class ProcessingProperties {

        private int batchSize = DEFAULT_BATCH_SIZE;
        private int laneCount = DEFAULT_LANE_COUNT;
        private RetryBackoffProperties retryBackoff = RetryBackoffProperties.withDefaults(
            DEFAULT_RETRY_FIXED_DELAY,
            DEFAULT_RETRY_BASE_DELAY,
            DEFAULT_RETRY_MULTIPLIER,
            DEFAULT_RETRY_INCREMENT,
            DEFAULT_RETRY_MAX_DELAY);

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getLaneCount() {
            return laneCount;
        }

        public void setLaneCount(int laneCount) {
            this.laneCount = laneCount;
        }

        public RetryBackoffProperties getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(RetryBackoffProperties retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
    }

    public static class RetryBackoffProperties {

        private Strategy strategy = EXPONENTIAL;
        private Duration fixedDelay;
        private Duration baseDelay;
        private double multiplier;
        private Duration increment;
        private Duration maxDelay;

        private static RetryBackoffProperties withDefaults(Duration fixedDelay, Duration baseDelay, double multiplier, Duration increment, Duration maxDelay) {
            var properties = new RetryBackoffProperties();
            properties.fixedDelay = fixedDelay;
            properties.baseDelay = baseDelay;
            properties.multiplier = multiplier;
            properties.increment = increment;
            properties.maxDelay = maxDelay;
            return properties;
        }

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
