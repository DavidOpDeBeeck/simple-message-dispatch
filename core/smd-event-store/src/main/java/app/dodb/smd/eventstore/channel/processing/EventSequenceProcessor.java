package app.dodb.smd.eventstore.channel.processing;

import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventInterceptorChain;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.eventstore.channel.RetryBackoffStrategy;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.store.SerializedEvent;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static app.dodb.smd.api.metadata.Metadata.withProperties;
import static app.dodb.smd.eventstore.channel.processing.EventSequenceProcessingResult.Abandoned;
import static app.dodb.smd.eventstore.channel.processing.EventSequenceProcessingResult.AlreadyProcessed;
import static app.dodb.smd.eventstore.channel.processing.EventSequenceProcessingResult.BackoffActive;
import static app.dodb.smd.eventstore.channel.processing.EventSequenceProcessingResult.Failure;
import static app.dodb.smd.eventstore.channel.processing.EventSequenceProcessingResult.Processed;
import static app.dodb.smd.eventstore.channel.processing.EventSequenceProcessingResult.SequenceClaimed;
import static java.util.Objects.requireNonNull;

class EventSequenceProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSequenceProcessor.class);

    private final EventSubjectSequenceStore eventSubjectSequenceStore;
    private final EventSerializer eventSerializer;
    private final List<EventInterceptor> interceptors;
    private final RetryBackoffStrategy retryBackoffStrategy;

    EventSequenceProcessor(EventSubjectSequenceStore eventSubjectSequenceStore,
                           EventSerializer eventSerializer,
                           List<EventInterceptor> interceptors,
                           RetryBackoffStrategy retryBackoffStrategy) {
        this.eventSubjectSequenceStore = requireNonNull(eventSubjectSequenceStore);
        this.eventSerializer = requireNonNull(eventSerializer);
        this.interceptors = requireNonNull(interceptors);
        this.retryBackoffStrategy = requireNonNull(retryBackoffStrategy);
    }

    EventSequenceProcessingResult process(EventChannelListener listener, SerializedEvent serializedEvent) {
        var processingGroup = listener.processingGroup();
        var sequenceNumber = serializedEvent.sequenceNumber();
        var subjectIdOpt = serializedEvent.subjectId();

        var claimedSequence = subjectIdOpt
            .map(subjectId -> eventSubjectSequenceStore.claimSubject(processingGroup, subjectId))
            .orElseGet(() -> eventSubjectSequenceStore.claimGlobal(processingGroup));
        if (claimedSequence.isEmpty()) {
            return new SequenceClaimed();
        }

        var sequence = claimedSequence.get();
        if (sequence.lastProcessedSequenceNumber().orElse(0L) >= sequenceNumber) {
            return new AlreadyProcessed();
        }

        if (sequence.abandoned()) {
            sequence.markSkipped(sequenceNumber);
            LOGGER.warn("Event abandoned because subjectId was already abandoned: processingGroup={}, subjectId={}, sequenceNumber={}, messageId={}",
                processingGroup, subjectIdOpt.orElse(null), sequenceNumber, serializedEvent.messageId());
            return new Abandoned();
        }

        if (sequence.failed()) {
            var lastErrorAt = sequence.lastErrorAt().orElseThrow(() -> new IllegalStateException(
                "Failed event sequence has no last error timestamp: processingGroup=" + processingGroup
                    + ", subjectId=" + subjectIdOpt.orElse(null)
            ));
            var nextAttemptAt = lastErrorAt.plus(retryBackoffStrategy.calculateDelay(sequence.errorCount() - 1));
            if (Instant.now().isBefore(nextAttemptAt)) {
                LOGGER.debug("Event subjectId backoff active: processingGroup={}, subjectId={}, sequenceNumber={}, nextAttemptAt={}",
                    processingGroup, subjectIdOpt.orElse(null), sequenceNumber, nextAttemptAt);
                return new BackoffActive();
            }
        }

        try {
            var eventMessage = eventSerializer.deserialize(serializedEvent);
            var eventProcessingMetadata = new HashMap<String, String>();
            eventProcessingMetadata.put("processingId", UUID.randomUUID().toString());
            eventProcessingMetadata.put("sequenceNumber", String.valueOf(sequenceNumber));
            eventProcessingMetadata.put("errorCount", String.valueOf(sequence.errorCount()));
            subjectIdOpt.ifPresent(subjectId -> eventProcessingMetadata.put("subjectId", subjectId));
            var eventMessageWithProcessingMetadata = eventMessage.andMetadata(withProperties(eventProcessingMetadata));

            MetadataFactory.runInScope(eventMessageWithProcessingMetadata, () -> {
                var chain = EventInterceptorChain.create(listener::on, interceptors);
                chain.proceed(eventMessageWithProcessingMetadata);
            });
        } catch (Exception e) {
            return new Failure(sequenceNumber, subjectIdOpt, e);
        }

        sequence.markProcessed(sequenceNumber);
        LOGGER.debug("Event processed: processingGroup={}, subjectId={}, sequenceNumber={}, messageId={}",
            processingGroup, subjectIdOpt.orElse(null), sequenceNumber, serializedEvent.messageId());

        return new Processed();
    }
}
