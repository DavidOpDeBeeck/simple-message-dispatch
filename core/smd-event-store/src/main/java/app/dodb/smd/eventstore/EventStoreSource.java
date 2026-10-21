package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static app.dodb.smd.eventstore.EventStoreConfig.SchedulingConfig;
import static java.time.Duration.ZERO;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class EventStoreSource implements EventSource, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreSource.class);

    private final SchedulingConfig schedulingConfig;
    private final EventStoreTokenProcessor tokenProcessor;
    private final Set<PollingSubscription> subscriptions = ConcurrentHashMap.newKeySet();

    EventStoreSource(EventStoreConfig config) {
        requireNonNull(config);
        this.schedulingConfig = config.getSchedulingConfig();
        this.tokenProcessor = new EventStoreTokenProcessor(config);
    }

    @Override
    public EventSubscription subscribe(EventSubscriber listener) {
        var subscription = new PollingSubscription(listener);
        subscriptions.add(subscription);
        if (!schedulingConfig.isEnabled()) {
            LOGGER.info("Event store polling disabled: processingGroup={}", listener.processingGroup());
            return subscription;
        }

        subscription.schedulePoll(schedulingConfig.getInitialDelay());
        return subscription;
    }

    boolean subscribes(String processingGroup) {
        return subscriptions.stream().anyMatch(subscription -> subscription.processingGroup().equals(processingGroup));
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }

    @Override
    public void close() {
        subscriptions.forEach(PollingSubscription::close);
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

    private class PollingSubscription implements EventSubscription {

        private final EventSubscriber listener;
        private ScheduledFuture<?> nextPoll;
        private boolean closed;

        private PollingSubscription(EventSubscriber listener) {
            this.listener = requireNonNull(listener);
        }

        private String processingGroup() {
            return listener.processingGroup();
        }

        // Each poll schedules the next one, so a group that is behind re-polls immediately
        // through the shared scheduler queue and other groups' polls still interleave.
        private void poll() {
            var backlogRemaining = tokenProcessor.poll(listener);
            schedulePoll(backlogRemaining ? ZERO : schedulingConfig.getPollingDelay());
        }

        private synchronized void schedulePoll(Duration delay) {
            if (closed) {
                return;
            }

            try {
                nextPoll = schedulingConfig.getScheduler().schedule(this::poll, delay.toMillis(), MILLISECONDS);
            } catch (RejectedExecutionException e) {
                LOGGER.debug("Event store scheduler rejected the next poll, polling stopped: processingGroup={}", processingGroup());
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            subscriptions.remove(this);
            if (nextPoll != null) {
                nextPoll.cancel(false);
            }
        }
    }
}
