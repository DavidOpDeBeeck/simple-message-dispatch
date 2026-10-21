package app.dodb.smd.spring.deadline;

import app.dodb.smd.spring.eventstore.SMDEventStoreProperties.ProcessingProperties.RetryBackoffProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

import static app.dodb.smd.eventstore.deadline.DeadlineStoreConfig.DEFAULT_BATCH_SIZE;
import static app.dodb.smd.eventstore.deadline.DeadlineStoreConfig.DEFAULT_INITIAL_DELAY;
import static app.dodb.smd.eventstore.deadline.DeadlineStoreConfig.DEFAULT_MAX_RETRIES;
import static app.dodb.smd.eventstore.deadline.DeadlineStoreConfig.DEFAULT_POLLING_DELAY;

@ConfigurationProperties(prefix = "smd.deadlines")
public class SMDDeadlineProperties {

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

        private boolean enabled = true;
        private Duration initialDelay = DEFAULT_INITIAL_DELAY;
        private Duration pollingDelay = DEFAULT_POLLING_DELAY;

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
    }

    public static class ProcessingProperties {

        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int batchSize = DEFAULT_BATCH_SIZE;
        @NestedConfigurationProperty
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

        public RetryBackoffProperties getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(RetryBackoffProperties retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
    }
}
