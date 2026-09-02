package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.event.channel.EventSource;
import app.dodb.smd.eventstore.channel.processing.EventStoreTokenProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.eventstore.channel.EventStoreConfig.SchedulingConfig;
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
    public void subscribe(EventChannelListener listener) {
        if (!schedulingConfig.isEnabled()) {
            LOGGER.info("Event store polling disabled: processingGroup={}", listener.processingGroup());
            return;
        }

        var scheduler = schedulingConfig.getScheduler();
        scheduler.scheduleWithFixedDelay(
            () -> tokenProcessor.poll(listener),
            schedulingConfig.getInitialDelay().toMillis(),
            schedulingConfig.getPollingDelay().toMillis(),
            MILLISECONDS
        );
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
