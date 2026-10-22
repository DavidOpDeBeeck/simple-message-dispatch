package app.dodb.smd.spring.durable;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

import static app.dodb.smd.eventstore.durable.DurableDeliveryConfig.DEFAULT_POLLING_DELAY;
import static app.dodb.smd.eventstore.durable.DurableDeliveryConfig.DEFAULT_STALL_WARNING_AFTER;
import static app.dodb.smd.eventstore.durable.DurableDeliveryConfig.DEFAULT_THREAD_POOL_SIZE;

@ConfigurationProperties(prefix = "smd.durable-delivery")
public class SMDDurableDeliveryProperties {

    private boolean enabled = false;
    private Duration pollingDelay = DEFAULT_POLLING_DELAY;
    private int threadPoolSize = DEFAULT_THREAD_POOL_SIZE;
    private Duration stallWarningAfter = DEFAULT_STALL_WARNING_AFTER;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public Duration getStallWarningAfter() {
        return stallWarningAfter;
    }

    public void setStallWarningAfter(Duration stallWarningAfter) {
        this.stallWarningAfter = stallWarningAfter;
    }
}
