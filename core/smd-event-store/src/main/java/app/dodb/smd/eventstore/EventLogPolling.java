package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class EventLogPolling implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventLogPolling.class);

    private final SchedulingConfig schedulingConfig;

    EventLogPolling(SchedulingConfig schedulingConfig) {
        this.schedulingConfig = requireNonNull(schedulingConfig);
    }

    EventSubscription schedule(EventStoreTokenProcessor processor, EventSubscriber subscriber) {
        if (!schedulingConfig.isEnabled()) {
            LOGGER.info("Event store polling disabled: processingGroup={}", subscriber.processingGroup());
            return EventSubscription.EMPTY;
        }

        var polling = schedulingConfig.getScheduler().scheduleWithFixedDelay(
            () -> processor.poll(subscriber),
            schedulingConfig.getInitialDelay().toMillis(),
            schedulingConfig.getPollingDelay().toMillis(),
            MILLISECONDS
        );
        return () -> polling.cancel(false);
    }

    @Override
    public void close() {
        var scheduler = schedulingConfig.getScheduler();
        scheduler.shutdown();
        try {
            if (scheduler.awaitTermination(30, SECONDS)) {
                return;
            }
            scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
