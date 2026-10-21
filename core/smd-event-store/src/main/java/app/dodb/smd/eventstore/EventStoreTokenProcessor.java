package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.EventSequenceProcessingResult.Failure;
import app.dodb.smd.eventstore.EventSequenceProcessingResult.Processed;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.Token;
import app.dodb.smd.eventstore.storage.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static java.time.Duration.between;
import static java.util.Objects.requireNonNull;

class EventStoreTokenProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreTokenProcessor.class);

    private final TransactionProvider transactionProvider;
    private final TokenStore tokenStore;
    private final EventStorage eventStorage;
    private final EventSubjectSequenceStore eventSubjectSequenceStore;
    private final ProcessingConfig processingConfig;
    private final EventSequenceProcessor eventSequenceProcessor;

    EventStoreTokenProcessor(EventStoreConfig config) {
        this.transactionProvider = requireNonNull(config.getTransactionProvider());
        this.tokenStore = requireNonNull(config.getTokenStore());
        this.eventStorage = requireNonNull(config.getEventStorage());
        this.processingConfig = requireNonNull(config.getProcessingConfig());
        this.eventSubjectSequenceStore = requireNonNull(config.getEventSequenceStore());
        this.eventSequenceProcessor = new EventSequenceProcessor(
            eventSubjectSequenceStore,
            config.getEventSerializer(),
            processingConfig.getRetryBackoffStrategy()
        );
    }

    boolean poll(EventSubscriber listener) {
        var processingGroup = listener.processingGroup();
        try {
            var batchSize = processingConfig.getBatchSize();
            while (batchSize > 0) {
                try {
                    var fullBatchAdvanced = processBatchInNewTransaction(listener, batchSize);
                    return fullBatchAdvanced && batchSize == processingConfig.getBatchSize();
                } catch (BatchProcessingFailure failure) {
                    if (failure.replayBatchSize() == 0) {
                        recordFailureInNewTransaction(processingGroup, failure.failure());
                        return false;
                    }

                    LOGGER.warn("Event processing failed mid-batch, retrying previous {} event(s): processingGroup={}, subjectId={}, sequenceNumber={}",
                        failure.replayBatchSize(), processingGroup, failure.failure().subjectId(), failure.failure().sequenceNumber(), failure.failure().exception());
                    batchSize = failure.replayBatchSize();
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Polling error: processingGroup={}", processingGroup, exception);
        }
        return false;
    }

    private boolean processBatchInNewTransaction(EventSubscriber listener, int batchSize) {
        return transactionProvider.doInNewTransaction(() -> {
            var claimedToken = tokenStore.claimToken(listener.processingGroup());
            if (claimedToken.isEmpty()) {
                LOGGER.debug("Token already claimed, skipping poll: processingGroup={}", listener.processingGroup());
                return false;
            }

            return processClaimedBatch(listener, claimedToken.get(), batchSize);
        });
    }

    private boolean processClaimedBatch(EventSubscriber listener, Token token, int batchSize) {
        long initialSequenceNumber = token.lastProcessedSequenceNumber().orElse(0L);
        long replayUntilSequenceNumber = token.replayUntilSequenceNumber().orElse(0L);
        long committableSequenceNumber = initialSequenceNumber;
        long lastInspectedSequenceNumber = initialSequenceNumber;
        int positionInBatch = 0;
        int lastProcessedPositionInBatch = 0;

        try (var events = eventStorage.load(initialSequenceNumber, batchSize)) {
            if (!events.hasNext()) {
                LOGGER.debug("No events to process: processingGroup={}, lastProcessedSequence={}", listener.processingGroup(), initialSequenceNumber);
                return false;
            }

            while (events.hasNext()) {
                var serializedEvent = events.next();
                var sequenceNumber = serializedEvent.sequenceNumber();
                var expectedSequence = lastInspectedSequenceNumber + 1;
                lastInspectedSequenceNumber = sequenceNumber;
                positionInBatch++;

                // The group already passed every position up to the replay boundary, so missing ones there are permanent holes.
                if (sequenceNumber > expectedSequence && expectedSequence <= replayUntilSequenceNumber) {
                    var skippedUntil = Math.min(sequenceNumber - 1, replayUntilSequenceNumber);
                    LOGGER.debug("Skipping missing replayed positions: processingGroup={}, fromSequence={}, toSequence={}",
                        listener.processingGroup(), expectedSequence, skippedUntil);
                    if (committableSequenceNumber + 1 == expectedSequence) {
                        committableSequenceNumber = skippedUntil;
                    }
                    expectedSequence = skippedUntil + 1;
                }

                if (sequenceNumber > expectedSequence) {
                    var gapDetectedAt = token.lastGapDetectedAt();
                    if (gapDetectedAt.isEmpty()) {
                        token.markGapDetected(expectedSequence);
                        LOGGER.warn("Gap detected: processingGroup={}, expectedSequence={}, actualSequence={}",
                            listener.processingGroup(), expectedSequence, sequenceNumber);
                        break;
                    }

                    if (between(gapDetectedAt.get(), Instant.now()).compareTo(processingConfig.getGapTimeout()) < 0) {
                        break;
                    }

                    LOGGER.error("Gap timeout expired, skipping: processingGroup={}, fromSequence={}, toSequence={}",
                        listener.processingGroup(), expectedSequence, sequenceNumber - 1);
                    if (committableSequenceNumber + 1 == expectedSequence) {
                        committableSequenceNumber = sequenceNumber - 1;
                    }
                }

                var result = eventSequenceProcessor.process(listener, serializedEvent, sequenceNumber <= replayUntilSequenceNumber);
                if (result instanceof Failure failure) {
                    throw new BatchProcessingFailure(failure, lastProcessedPositionInBatch);
                }
                if (result instanceof Processed) {
                    lastProcessedPositionInBatch = positionInBatch;
                }
                if (result.advancesToken() && committableSequenceNumber + 1 == sequenceNumber) {
                    committableSequenceNumber = sequenceNumber;
                }
            }
        }

        if (committableSequenceNumber <= initialSequenceNumber) {
            return false;
        }

        markProcessed(listener.processingGroup(), token, committableSequenceNumber);
        return positionInBatch == batchSize;
    }

    private void recordFailureInNewTransaction(String processingGroup, Failure failure) {
        transactionProvider.doInNewTransaction(() -> {
            var claimedToken = tokenStore.claimToken(processingGroup);
            if (claimedToken.isEmpty()) {
                LOGGER.debug("Token already claimed, skipping failure record: processingGroup={}", processingGroup);
                return;
            }

            var claimedSequence = failure.subjectId()
                .map(subjectId -> eventSubjectSequenceStore.claimSubject(processingGroup, subjectId))
                .orElseGet(() -> eventSubjectSequenceStore.claimGlobal(processingGroup));
            if (claimedSequence.isEmpty()) {
                LOGGER.debug("Event subjectId already claimed, skipping failure record: processingGroup={}, subjectId={}, sequenceNumber={}",
                    processingGroup, failure.subjectId().orElse(null), failure.sequenceNumber());
                return;
            }

            var token = claimedToken.orElseThrow();
            var sequence = claimedSequence.orElseThrow();
            if (sequence.errorCount() >= processingConfig.getMaxRetries()) {
                LOGGER.error("Event abandoned (retries exhausted): processingGroup={}, subjectId={}, sequenceNumber={}, maxRetries={}",
                    processingGroup, failure.subjectId().orElse(null), failure.sequenceNumber(), processingConfig.getMaxRetries(), failure.exception());
                sequence.markAbandoned(failure.sequenceNumber(), failure.exception());

                if (token.lastProcessedSequenceNumber().orElse(0L) + 1 == failure.sequenceNumber()) {
                    markProcessed(processingGroup, token, failure.sequenceNumber());
                }
                return;
            }

            var nextErrorCount = sequence.errorCount() + 1;
            LOGGER.warn("Event processing failed: processingGroup={}, subjectId={}, sequenceNumber={}, retry={}/{}",
                processingGroup, failure.subjectId().orElse(null), failure.sequenceNumber(), nextErrorCount, processingConfig.getMaxRetries(), failure.exception());
            sequence.markFailed(failure.exception(), nextErrorCount);
        });
    }

    private static void markProcessed(String processingGroup, Token token, long sequenceNumber) {
        var replayUntilSequenceNumber = token.replayUntilSequenceNumber();
        token.markProcessed(sequenceNumber);
        if (replayUntilSequenceNumber.filter(replayUntil -> sequenceNumber >= replayUntil).isPresent()) {
            LOGGER.info("Replay finished: processingGroup={}, replayUntil={}, sequenceNumber={}",
                processingGroup, replayUntilSequenceNumber.get(), sequenceNumber);
        }
    }

    private static class BatchProcessingFailure extends RuntimeException {

        private final Failure failure;
        private final int replayBatchSize;

        BatchProcessingFailure(Failure failure, int replayBatchSize) {
            super(failure.exception());
            this.failure = requireNonNull(failure);
            this.replayBatchSize = replayBatchSize;
        }

        Failure failure() {
            return failure;
        }

        int replayBatchSize() {
            return replayBatchSize;
        }
    }
}
