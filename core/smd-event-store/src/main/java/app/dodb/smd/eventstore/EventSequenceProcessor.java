package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.MetadataProperties;
import app.dodb.smd.eventstore.sequence.EventSequenceStore;
import app.dodb.smd.eventstore.storage.StoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static app.dodb.smd.api.event.delivery.ProcessingMetadata.ERROR_COUNT;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.PROCESSING_ID;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.SEQUENCE_NUMBER;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.SUBJECT_ID;
import static app.dodb.smd.api.metadata.Metadata.withProperties;
import static app.dodb.smd.eventstore.EventSequenceProcessingResult.Abandoned;
import static app.dodb.smd.eventstore.EventSequenceProcessingResult.AlreadyProcessed;
import static app.dodb.smd.eventstore.EventSequenceProcessingResult.BackoffActive;
import static app.dodb.smd.eventstore.EventSequenceProcessingResult.Failure;
import static app.dodb.smd.eventstore.EventSequenceProcessingResult.Processed;
import static app.dodb.smd.eventstore.EventSequenceProcessingResult.SequenceClaimed;
import static java.util.Objects.requireNonNull;

class EventSequenceProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSequenceProcessor.class);

    private final EventSequenceStore eventSequenceStore;
    private final StoredEventSerializer storedEventSerializer;
    private final RetryBackoffStrategy retryBackoffStrategy;

    EventSequenceProcessor(EventSequenceStore eventSequenceStore,
                           StoredEventSerializer storedEventSerializer,
                           RetryBackoffStrategy retryBackoffStrategy) {
        this.eventSequenceStore = requireNonNull(eventSequenceStore);
        this.storedEventSerializer = requireNonNull(storedEventSerializer);
        this.retryBackoffStrategy = requireNonNull(retryBackoffStrategy);
    }

    EventSequenceProcessingResult process(EventSubscriber listener, StoredEvent storedEvent) {
        var processingGroup = listener.name();
        var globalPosition = storedEvent.globalPosition();
        var aggregateIdOpt = storedEvent.aggregateId();

        var claimedSequence = aggregateIdOpt
            .map(aggregateId -> eventSequenceStore.claimAggregate(processingGroup, aggregateId))
            .orElseGet(() -> eventSequenceStore.claimGlobal(processingGroup));
        if (claimedSequence.isEmpty()) {
            return new SequenceClaimed();
        }

        var sequence = claimedSequence.get();
        if (sequence.lastProcessedPosition().orElse(0L) >= globalPosition) {
            return new AlreadyProcessed();
        }

        if (sequence.abandoned()) {
            sequence.markSkipped(globalPosition);
            LOGGER.warn("Event abandoned because its aggregate was already abandoned: processingGroup={}, aggregateId={}, globalPosition={}, eventId={}",
                processingGroup, aggregateIdOpt.orElse(null), globalPosition, storedEvent.eventId());
            return new Abandoned();
        }

        if (sequence.failed()) {
            var lastErrorAt = sequence.lastErrorAt().orElseThrow(() -> new IllegalStateException(
                "Failed event sequence has no last error timestamp: processingGroup=" + processingGroup
                    + ", aggregateId=" + aggregateIdOpt.orElse(null)
            ));
            var nextAttemptAt = lastErrorAt.plus(retryBackoffStrategy.calculateDelay(sequence.errorCount() - 1));
            if (Instant.now().isBefore(nextAttemptAt)) {
                LOGGER.debug("Event sequence backoff active: processingGroup={}, aggregateId={}, globalPosition={}, nextAttemptAt={}",
                    processingGroup, aggregateIdOpt.orElse(null), globalPosition, nextAttemptAt);
                return new BackoffActive();
            }
        }

        try {
            var eventMessage = storedEventSerializer.deserialize(storedEvent);
            var processingProperties = MetadataProperties.of(
                PROCESSING_ID, UUID.randomUUID().toString(),
                SEQUENCE_NUMBER, String.valueOf(globalPosition),
                ERROR_COUNT, String.valueOf(sequence.errorCount())
            );
            var properties = aggregateIdOpt.map(aggregateId -> processingProperties.with(SUBJECT_ID, aggregateId))
                .orElse(processingProperties);
            var eventMessageWithProcessingMetadata = eventMessage.andMetadata(withProperties(properties));

            MetadataFactory.runInScope(eventMessageWithProcessingMetadata, () -> listener.on(eventMessageWithProcessingMetadata));
        } catch (Exception e) {
            return new Failure(globalPosition, aggregateIdOpt, e);
        }

        sequence.markProcessed(globalPosition);
        LOGGER.debug("Event processed: processingGroup={}, aggregateId={}, globalPosition={}, eventId={}",
            processingGroup, aggregateIdOpt.orElse(null), globalPosition, storedEvent.eventId());

        return new Processed();
    }
}
