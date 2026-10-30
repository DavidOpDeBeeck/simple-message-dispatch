package app.dodb.smd.durable;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.MetadataProperties;
import app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.durable.storage.PendingDelivery;
import app.dodb.smd.serialization.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static app.dodb.smd.api.event.delivery.ProcessingMetadata.ERROR_COUNT;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.PROCESSING_ID;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.SUBJECT_ID;
import static app.dodb.smd.api.metadata.Metadata.withProperties;
import static java.util.Objects.requireNonNull;

class DurableEventDispatcherSource implements EventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(DurableEventDispatcherSource.class);

    private final String dispatcher;
    private final DispatcherStorage storage;
    private final TransactionProvider transactionProvider;
    private final EventSerializer eventSerializer;
    private final SchedulingConfig schedulingConfig;
    private final int batchSize;
    private final int laneCount;
    private final RetryBackoffStrategy retryBackoffStrategy;

    DurableEventDispatcherSource(String dispatcher, DurableEventDispatcherConfig config) {
        requireNonNull(config);
        this.dispatcher = requireNonNull(dispatcher);
        this.storage = config.getDispatcherStorage();
        this.transactionProvider = config.getTransactionProvider();
        this.eventSerializer = config.getEventSerializer();
        this.schedulingConfig = config.getSchedulingConfig();
        this.batchSize = config.getProcessingConfig().getBatchSize();
        this.laneCount = config.getProcessingConfig().getLaneCount();
        this.retryBackoffStrategy = config.getProcessingConfig().getRetryBackoffStrategy();
    }

    @Override
    public EventSubscription subscribe(EventSubscriber subscriber) {
        transactionProvider.doInNewTransaction(() -> register(subscriber));

        var delivery = new SubscriberDelivery(subscriber);
        return Worker.start(delivery::deliverNextBatch, schedulingConfig);
    }

    @Override
    public EventDelivery delivery() {
        return DurableEventDispatcher.DELIVERY;
    }

    private void register(EventSubscriber subscriber) {
        var registeredLaneCount = storage.registerDispatcher(dispatcher, laneCount);
        if (registeredLaneCount != laneCount) {
            throw new IllegalStateException("Durable event dispatcher '%s' is configured with %d lanes, but its storage has %d lanes; subscriber '%s' cannot subscribe until they match"
                .formatted(dispatcher, laneCount, registeredLaneCount, subscriber.name()));
        }
        storage.registerSubscriber(dispatcher, subscriber.name());
    }

    private class SubscriberDelivery {

        private final EventSubscriber subscriber;
        private final String subscriberName;

        private SubscriberDelivery(EventSubscriber subscriber) {
            this.subscriber = requireNonNull(subscriber);
            this.subscriberName = subscriber.name();
        }

        private boolean deliverNextBatch() {
            try {
                return transactionProvider.doInNewTransaction(this::deliverClaimed);
            } catch (HandlerFailure failure) {
                recordFailure(failure.delivery, failure.getCause());
                return true;
            }
        }

        private boolean deliverClaimed() {
            var deliveries = storage.claimBatch(dispatcher, subscriberName, batchSize);
            for (var delivery : deliveries) {
                handle(delivery);
                storage.markDelivered(dispatcher, subscriberName, delivery);
            }
            return !deliveries.isEmpty();
        }

        private void handle(PendingDelivery delivery) {
            try {
                var event = delivery.event();
                var eventMessage = eventSerializer.deserialize(event);
                var properties = MetadataProperties.builder()
                    .with(PROCESSING_ID, UUID.randomUUID().toString())
                    .with(ERROR_COUNT, String.valueOf(delivery.attempts()))
                    .with(SUBJECT_ID, event.subjectId())
                    .build();
                var eventMessageWithProcessingMetadata = eventMessage.andMetadata(withProperties(properties));

                MetadataFactory.runInScope(eventMessageWithProcessingMetadata, () -> subscriber.on(eventMessageWithProcessingMetadata));
            } catch (Throwable e) {
                throw new HandlerFailure(delivery, e);
            }
        }

        private void recordFailure(PendingDelivery delivery, Throwable failure) {
            var retryDelay = retryBackoffStrategy.calculateDelay(delivery.attempts());
            transactionProvider.doInNewTransaction(() -> storage.recordFailure(dispatcher, subscriberName, delivery, retryDelay, failure));
            LOGGER.warn("Durable delivery failed, retrying in {}: dispatcher={}, subscriber={}, messageId={}, attempt={}",
                retryDelay, dispatcher, subscriberName, delivery.event().messageId().value(), delivery.attempts() + 1, failure);
        }
    }

    private static class HandlerFailure extends RuntimeException {

        private final PendingDelivery delivery;

        private HandlerFailure(PendingDelivery delivery, Throwable cause) {
            super(cause);
            this.delivery = requireNonNull(delivery);
        }
    }
}
