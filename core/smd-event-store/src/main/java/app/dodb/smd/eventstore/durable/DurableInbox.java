package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.framework.TransactionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class DurableInbox implements EventSource, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableInbox.class);

    private final String name;
    private final InboxStorage storage;
    private final DurableDeliveryConfig config;
    private final TransactionProvider transactionProvider;
    private final Set<String> processingGroups = ConcurrentHashMap.newKeySet();
    private final List<EventSubscription> transportSubscriptions = new ArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    DurableInbox(String name, List<TransportSource> sources, InboxStorage storage, DurableDeliveryConfig config) {
        this.name = requireNonNull(name);
        this.storage = requireNonNull(storage);
        this.config = requireNonNull(config);
        this.transactionProvider = config.getTransactionProvider();
        try {
            for (var source : sources) {
                transportSubscriptions.add(source.subscribe(name, this::receive));
            }
        } catch (RuntimeException e) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override
    public EventSubscription subscribe(EventSubscriber subscriber) {
        var processingGroup = subscriber.processingGroup();
        if (processingGroup.isBlank() || processingGroup.length() > 255) {
            throw new IllegalArgumentException("Inbox '" + name + "' requires a processing group name of 1 to 255 characters; got '" + processingGroup + "'");
        }
        if (!processingGroups.add(processingGroup)) {
            throw new IllegalStateException("Processing group '" + processingGroup + "' is already subscribed to inbox '" + name + "'");
        }
        transactionProvider.doInNewTransaction(() -> storage.register(name, processingGroup));

        var scheduler = config.getScheduler();
        var pollingDelay = config.getPollingDelay().toMillis();
        var stallWarningAfter = config.getStallWarningAfter();
        ScheduledFuture<?> delivering = scheduler.scheduleWithFixedDelay(
            new InboxDelivery(name, subscriber, storage, config), 0, pollingDelay, MILLISECONDS);
        ScheduledFuture<?> stallChecking = scheduler.scheduleWithFixedDelay(
            () -> warnAboutStalledSubjects(processingGroup), stallWarningAfter.toMillis(), stallWarningAfter.toMillis(), MILLISECONDS);
        return () -> {
            delivering.cancel(false);
            stallChecking.cancel(false);
            processingGroups.remove(processingGroup);
        };
    }

    @Override
    public EventDelivery delivery() {
        return DurableDelivery.INBOX;
    }

    @Override
    public void close() {
        closed.countDown();
        RuntimeException primary = null;
        for (var subscription : transportSubscriptions) {
            try {
                subscription.close();
            } catch (RuntimeException e) {
                if (primary == null) {
                    primary = e;
                } else {
                    primary.addSuppressed(e);
                }
            }
        }
        if (primary != null) {
            throw primary;
        }
    }

    // Runs on the transport's thread. A storage failure is retried here instead of rejecting the message, so the broker never
    // redelivers it out of order or moves it to a dead-letter address. Only closing the inbox gives the message back unacknowledged.
    private void receive(TransportEvent event) {
        for (int attempt = 0; ; attempt++) {
            try {
                transactionProvider.doInNewTransaction(() -> storage.store(name, event));
                if (attempt > 0) {
                    LOGGER.info("Inbox storage recovered: inbox={}, messageId={}, attempts={}", name, event.event().messageId().value(), attempt);
                }
                return;
            } catch (RuntimeException e) {
                var retryDelay = config.getRetryBackoffStrategy().calculateDelay(attempt);
                LOGGER.warn("Inbox storage failed, retrying in {}: inbox={}, messageId={}, attempt={}",
                    retryDelay, name, event.event().messageId().value(), attempt + 1, e);
                if (awaitClose(retryDelay.toMillis())) {
                    throw e;
                }
            }
        }
    }

    private boolean awaitClose(long millis) {
        try {
            return closed.await(millis, MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private void warnAboutStalledSubjects(String processingGroup) {
        try {
            var stalledSubjects = transactionProvider.doInNewTransaction(
                () -> storage.findStalledSubjects(name, processingGroup, config.getStallWarningAfter()));
            for (var stalled : stalledSubjects) {
                LOGGER.warn("Inbox subject is waiting for a missing position: inbox={}, processingGroup={}, origin={}, subjectId={}, "
                        + "missingPosition={}, nextAvailablePosition={}, waitingSince={}",
                    name, processingGroup, stalled.origin(), stalled.subjectId(), stalled.nextPosition(), stalled.firstAvailablePosition(),
                    stalled.waitingSince());
            }
        } catch (RuntimeException e) {
            LOGGER.error("Inbox stall check failed: inbox={}, processingGroup={}", name, processingGroup, e);
        }
    }
}
