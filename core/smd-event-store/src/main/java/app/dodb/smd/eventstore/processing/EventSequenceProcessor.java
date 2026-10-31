package app.dodb.smd.eventstore.processing;

import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.MetadataProperties;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.serialization.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static app.dodb.smd.api.event.delivery.ProcessingMetadata.ERROR_COUNT;
import static app.dodb.smd.api.event.delivery.ProcessingMetadata.PROCESSING_ID;
import static app.dodb.smd.eventstore.EventStoreMetadata.GLOBAL_POSITION;
import static app.dodb.smd.eventstore.processing.EventSequenceProcessingResult.Abandoned;
import static app.dodb.smd.eventstore.processing.EventSequenceProcessingResult.AlreadyProcessed;
import static app.dodb.smd.eventstore.processing.EventSequenceProcessingResult.BackoffActive;
import static app.dodb.smd.eventstore.processing.EventSequenceProcessingResult.Failure;
import static app.dodb.smd.eventstore.processing.EventSequenceProcessingResult.Processed;
import static app.dodb.smd.eventstore.processing.EventSequenceProcessingResult.SequenceClaimed;
import static java.util.Objects.requireNonNull;

class EventSequenceProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSequenceProcessor.class);

    private final EventSequenceStore eventSequenceStore;
    private final EventSerializer eventSerializer;
    private final RetryBackoffStrategy retryBackoffStrategy;

    EventSequenceProcessor(EventSequenceStore eventSequenceStore,
                           EventSerializer eventSerializer,
                           RetryBackoffStrategy retryBackoffStrategy) {
        this.eventSequenceStore = requireNonNull(eventSequenceStore);
        this.eventSerializer = requireNonNull(eventSerializer);
        this.retryBackoffStrategy = requireNonNull(retryBackoffStrategy);
    }

    EventSequenceProcessingResult process(EventSubscriber listener, StoredEvent storedEvent) {
        var processingGroup = listener.name();
        var globalPosition = storedEvent.globalPosition();
        var sequenceId = sequenceId(storedEvent);

        var claimedSequence = eventSequenceStore.claim(processingGroup, sequenceId);
        if (claimedSequence.isEmpty()) {
            return new SequenceClaimed();
        }

        var sequence = claimedSequence.get();
        if (sequence.lastProcessedPosition().orElse(0L) >= globalPosition) {
            return new AlreadyProcessed();
        }

        if (sequence.abandoned()) {
            sequence.markSkipped(globalPosition);
            LOGGER.warn("Event abandoned because its sequence was already abandoned: processingGroup={}, sequence={}, globalPosition={}, eventId={}",
                processingGroup, sequenceId, globalPosition, storedEvent.eventId());
            return new Abandoned();
        }

        if (sequence.failed()) {
            var lastErrorAt = sequence.lastErrorAt().orElseThrow(() -> new IllegalStateException(
                "Failed event sequence has no last error timestamp: processingGroup=" + processingGroup + ", sequence=" + sequenceId
            ));
            var nextAttemptAt = lastErrorAt.plus(retryBackoffStrategy.calculateDelay(sequence.errorCount() - 1));
            if (Instant.now().isBefore(nextAttemptAt)) {
                LOGGER.debug("Event sequence backoff active: processingGroup={}, sequence={}, globalPosition={}, nextAttemptAt={}",
                    processingGroup, sequenceId, globalPosition, nextAttemptAt);
                return new BackoffActive();
            }
        }

        try {
            var eventMessage = eventSerializer.deserialize(storedEvent.serializedEvent());
            var properties = MetadataProperties.builder()
                .with(PROCESSING_ID, UUID.randomUUID().toString())
                .with(GLOBAL_POSITION, String.valueOf(globalPosition))
                .with(ERROR_COUNT, String.valueOf(sequence.errorCount()))
                .build();
            var eventMessageWithProcessingMetadata = eventMessage.withMetadata(metadata -> metadata.withTransientProperties(properties));

            MetadataFactory.runInScope(eventMessageWithProcessingMetadata, () -> listener.on(eventMessageWithProcessingMetadata));
        } catch (Exception e) {
            return new Failure(globalPosition, sequenceId, e);
        }

        sequence.markProcessed(globalPosition);
        LOGGER.debug("Event processed: processingGroup={}, sequence={}, globalPosition={}, eventId={}",
            processingGroup, sequenceId, globalPosition, storedEvent.eventId());

        return new Processed();
    }

    private static EventSequenceId sequenceId(StoredEvent storedEvent) {
        return storedEvent.subject()
            .<EventSequenceId>map(EventSequenceId.OfSubject::new)
            .orElseGet(EventSequenceId.Global::new);
    }
}
