package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

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

    private final EventLog log;
    private final EventLogConsumption consumption;
    private final EventSubjectSequenceStore eventSubjectSequenceStore;
    private final EventSerializer eventSerializer;
    private final RetryBackoffStrategy retryBackoffStrategy;

    EventSequenceProcessor(EventLog log,
                           EventLogConsumption consumption,
                           EventSubjectSequenceStore eventSubjectSequenceStore,
                           EventSerializer eventSerializer,
                           RetryBackoffStrategy retryBackoffStrategy) {
        this.log = requireNonNull(log);
        this.consumption = requireNonNull(consumption);
        this.eventSubjectSequenceStore = requireNonNull(eventSubjectSequenceStore);
        this.eventSerializer = requireNonNull(eventSerializer);
        this.retryBackoffStrategy = requireNonNull(retryBackoffStrategy);
    }

    EventSequenceProcessingResult process(EventSubscriber listener, SerializedEvent serializedEvent) {
        var processingGroup = listener.processingGroup();
        var sequenceNumber = serializedEvent.sequenceNumber();
        var subjectIdOpt = serializedEvent.subjectId();

        var claimedSequence = subjectIdOpt
            .map(subjectId -> eventSubjectSequenceStore.claimSubject(log, processingGroup, subjectId))
            .orElseGet(() -> eventSubjectSequenceStore.claimGlobal(log, processingGroup));
        if (claimedSequence.isEmpty()) {
            return new SequenceClaimed();
        }

        var sequence = claimedSequence.get();
        if (sequence.lastProcessedSequenceNumber().orElse(0L) >= sequenceNumber) {
            return new AlreadyProcessed();
        }

        if (sequence.abandoned()) {
            sequence.markSkipped(sequenceNumber);
            LOGGER.warn("Event abandoned because subjectId was already abandoned: log={}, processingGroup={}, subjectId={}, sequenceNumber={}, messageId={}",
                log.name(), processingGroup, subjectIdOpt.orElse(null), sequenceNumber, serializedEvent.messageId());
            return new Abandoned();
        }

        if (sequence.failed()) {
            var lastErrorAt = sequence.lastErrorAt().orElseThrow(() -> new IllegalStateException(
                "Failed event sequence has no last error timestamp: log=" + log.name() + ", processingGroup=" + processingGroup
                    + ", subjectId=" + subjectIdOpt.orElse(null)
            ));
            var nextAttemptAt = lastErrorAt.plus(retryBackoffStrategy.calculateDelay(sequence.errorCount() - 1));
            if (Instant.now().isBefore(nextAttemptAt)) {
                LOGGER.debug("Event subjectId backoff active: log={}, processingGroup={}, subjectId={}, sequenceNumber={}, nextAttemptAt={}",
                    log.name(), processingGroup, subjectIdOpt.orElse(null), sequenceNumber, nextAttemptAt);
                return new BackoffActive();
            }
        }

        try {
            var eventMessage = consumption.addsAttemptMetadata()
                ? withAttemptMetadata(eventSerializer.deserialize(serializedEvent), sequenceNumber, sequence.errorCount())
                : eventSerializer.deserialize(serializedEvent);

            MetadataFactory.runInScope(eventMessage, () -> listener.on(eventMessage));
        } catch (Exception e) {
            return new Failure(sequenceNumber, subjectIdOpt, e);
        }

        sequence.markProcessed(sequenceNumber);
        LOGGER.debug("Event processed: log={}, processingGroup={}, subjectId={}, sequenceNumber={}, messageId={}",
            log.name(), processingGroup, subjectIdOpt.orElse(null), sequenceNumber, serializedEvent.messageId());

        return new Processed();
    }

    private static <E extends Event> EventMessage<E> withAttemptMetadata(EventMessage<E> eventMessage, long sequenceNumber, int errorCount) {
        var attemptMetadata = new HashMap<String, String>();
        attemptMetadata.put("processingId", UUID.randomUUID().toString());
        attemptMetadata.put("sequenceNumber", String.valueOf(sequenceNumber));
        attemptMetadata.put("errorCount", String.valueOf(errorCount));
        eventMessage.subjectId().ifPresent(subjectId -> attemptMetadata.put("subjectId", subjectId));
        return eventMessage.andMetadata(withProperties(attemptMetadata));
    }
}
