package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class EventStoreSource implements EventSource, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreSource.class);

    private final SchedulingConfig schedulingConfig;
    private final EventStoreTokenProcessor tokenProcessor;

    EventStoreSource(EventStoreConfig config) {
        requireNonNull(config);
        this.schedulingConfig = config.getSchedulingConfig();
        this.tokenProcessor = new EventStoreTokenProcessor(config);
    }

    @Override
    public EventSubscription subscribe(EventSubscriber listener) {
        if (!schedulingConfig.isEnabled()) {
            LOGGER.info("Event store polling disabled: processingGroup={}", listener.processingGroup());
            return EventSubscription.EMPTY;
        }

        var scheduler = schedulingConfig.getScheduler();
        var polling = scheduler.scheduleWithFixedDelay(
            () -> tokenProcessor.poll(listener),
            schedulingConfig.getInitialDelay().toMillis(),
            schedulingConfig.getPollingDelay().toMillis(),
            MILLISECONDS
        );
        return () -> polling.cancel(false);
    }

    @Override
    public void close() {
        ScheduledExecutorService scheduler = schedulingConfig.getScheduler();
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
