package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.eventstore.RetryBackoffStrategy;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.UUID;

import static app.dodb.smd.api.metadata.Metadata.withProperties;
import static java.util.Objects.requireNonNull;

class InboxDelivery implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxDelivery.class);
    private static final int MAX_DELIVERIES_PER_POLL = 100;

    private final String inbox;
    private final EventSubscriber subscriber;
    private final InboxStorage storage;
    private final TransactionProvider transactionProvider;
    private final EventSerializer eventSerializer;
    private final RetryBackoffStrategy retryBackoffStrategy;

    InboxDelivery(String inbox, EventSubscriber subscriber, InboxStorage storage, DurableDeliveryConfig config) {
        this.inbox = requireNonNull(inbox);
        this.subscriber = requireNonNull(subscriber);
        this.storage = requireNonNull(storage);
        this.transactionProvider = config.getTransactionProvider();
        this.eventSerializer = config.getEventSerializer();
        this.retryBackoffStrategy = config.getRetryBackoffStrategy();
    }

    @Override
    public void run() {
        try {
            for (int deliveries = 0; deliveries < MAX_DELIVERIES_PER_POLL && !Thread.currentThread().isInterrupted(); deliveries++) {
                if (!deliverNext()) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Inbox polling failed: inbox={}, processingGroup={}", inbox, subscriber.processingGroup(), e);
        }
    }

    // The handler runs in the claiming transaction, so its changes commit together with the pending row's removal and the cursor advance.
    private boolean deliverNext() {
        var processingGroup = subscriber.processingGroup();
        try {
            return transactionProvider.doInNewTransaction(() -> storage.claimNext(inbox, processingGroup)
                .map(entry -> {
                    handle(entry);
                    storage.markDelivered(inbox, processingGroup, entry);
                    if (entry.attempts() > 0) {
                        LOGGER.info("Inbox delivery recovered: inbox={}, processingGroup={}, messageId={}, attempts={}",
                            inbox, processingGroup, entry.event().event().messageId().value(), entry.attempts());
                    }
                    return true;
                })
                .orElse(false));
        } catch (HandlingFailure failure) {
            var entry = failure.entry;
            var retryDelay = retryBackoffStrategy.calculateDelay(entry.attempts());
            transactionProvider.doInNewTransaction(() -> storage.recordFailure(inbox, processingGroup, entry, retryDelay, failure.cause()));
            LOGGER.warn("Inbox delivery failed, retrying in {}: inbox={}, processingGroup={}, messageId={}, subjectId={}, attempt={}",
                retryDelay, inbox, processingGroup, entry.event().event().messageId().value(), entry.event().event().subjectId().orElse(null),
                entry.attempts() + 1, failure.cause());
            return true;
        }
    }

    private void handle(InboxEntry entry) {
        try {
            var serializedEvent = entry.event().event();
            var eventMessage = eventSerializer.deserialize(serializedEvent);
            var processingMetadata = new HashMap<String, String>();
            processingMetadata.put("processingId", UUID.randomUUID().toString());
            processingMetadata.put("errorCount", String.valueOf(entry.attempts()));
            serializedEvent.subjectId().ifPresent(subjectId -> processingMetadata.put("subjectId", subjectId));
            var eventMessageWithProcessingMetadata = eventMessage.andMetadata(withProperties(processingMetadata));

            MetadataFactory.runInScope(eventMessageWithProcessingMetadata, () -> subscriber.on(eventMessageWithProcessingMetadata));
        } catch (RuntimeException e) {
            throw new HandlingFailure(entry, e);
        }
    }

    private static class HandlingFailure extends RuntimeException {

        private final InboxEntry entry;

        private HandlingFailure(InboxEntry entry, RuntimeException cause) {
            super(cause);
            this.entry = entry;
        }

        private Exception cause() {
            return (Exception) getCause();
        }
    }
}
