package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.EventSequenceProcessingResult.Failure;
import app.dodb.smd.eventstore.EventSequenceProcessingResult.Processed;
import app.dodb.smd.eventstore.EventStoreConfig.ProcessingConfig;
import app.dodb.smd.eventstore.sequence.EventSequenceStore;
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
    private final EventSequenceStore eventSequenceStore;
    private final ProcessingConfig processingConfig;
    private final EventSequenceProcessor eventSequenceProcessor;

    EventStoreTokenProcessor(EventStoreConfig config) {
        this.transactionProvider = requireNonNull(config.getTransactionProvider());
        this.tokenStore = requireNonNull(config.getTokenStore());
        this.eventStorage = requireNonNull(config.getEventStorage());
        this.processingConfig = requireNonNull(config.getProcessingConfig());
        this.eventSequenceStore = requireNonNull(config.getEventSequenceStore());
        this.eventSequenceProcessor = new EventSequenceProcessor(
            eventSequenceStore,
            new StoredEventSerializer(config.getEventSerializer()),
            processingConfig.getRetryBackoffStrategy()
        );
    }

    void poll(EventSubscriber listener) {
        var processingGroup = listener.name();
        try {
            var batchSize = processingConfig.getBatchSize();
            while (batchSize > 0) {
                try {
                    processBatchInNewTransaction(listener, batchSize);
                    return;
                } catch (BatchProcessingFailure failure) {
                    if (failure.replayBatchSize() == 0) {
                        recordFailureInNewTransaction(processingGroup, failure.failure());
                        return;
                    }

                    LOGGER.warn("Event processing failed mid-batch, retrying previous {} event(s): processingGroup={}, aggregateId={}, globalPosition={}",
                        failure.replayBatchSize(), processingGroup, failure.failure().aggregateId(), failure.failure().globalPosition(), failure.failure().exception());
                    batchSize = failure.replayBatchSize();
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Polling error: processingGroup={}", processingGroup, exception);
        }
    }

    private void processBatchInNewTransaction(EventSubscriber listener, int batchSize) {
        transactionProvider.doInNewTransaction(() -> {
            var claimedToken = tokenStore.claimToken(listener.name());
            if (claimedToken.isEmpty()) {
                LOGGER.debug("Token already claimed, skipping poll: processingGroup={}", listener.name());
                return;
            }

            processClaimedBatch(listener, claimedToken.get(), batchSize);
        });
    }

    private void processClaimedBatch(EventSubscriber listener, Token token, int batchSize) {
        long initialPosition = token.lastProcessedPosition().orElse(0L);
        long committablePosition = initialPosition;
        long lastInspectedPosition = initialPosition;
        int indexInBatch = 0;
        int lastProcessedIndexInBatch = 0;

        try (var events = eventStorage.load(initialPosition, batchSize)) {
            if (!events.hasNext()) {
                LOGGER.debug("No events to process: processingGroup={}, lastProcessedPosition={}", listener.name(), initialPosition);
                return;
            }

            while (events.hasNext()) {
                var storedEvent = events.next();
                var globalPosition = storedEvent.globalPosition();
                var expectedPosition = lastInspectedPosition + 1;
                lastInspectedPosition = globalPosition;
                indexInBatch++;

                if (globalPosition > expectedPosition) {
                    var gapDetectedAt = token.lastGapDetectedAt();
                    if (gapDetectedAt.isEmpty()) {
                        token.markGapDetected(expectedPosition);
                        LOGGER.warn("Gap detected: processingGroup={}, expectedPosition={}, actualPosition={}",
                            listener.name(), expectedPosition, globalPosition);
                        break;
                    }

                    if (between(gapDetectedAt.get(), Instant.now()).compareTo(processingConfig.getGapTimeout()) < 0) {
                        break;
                    }

                    LOGGER.error("Gap timeout expired, skipping: processingGroup={}, fromPosition={}, toPosition={}",
                        listener.name(), expectedPosition, globalPosition - 1);
                    if (committablePosition + 1 == expectedPosition) {
                        committablePosition = globalPosition - 1;
                    }
                }

                var result = eventSequenceProcessor.process(listener, storedEvent);
                if (result instanceof Failure failure) {
                    throw new BatchProcessingFailure(failure, lastProcessedIndexInBatch);
                }
                if (result instanceof Processed) {
                    lastProcessedIndexInBatch = indexInBatch;
                }
                if (result.advancesToken() && committablePosition + 1 == globalPosition) {
                    committablePosition = globalPosition;
                }
            }
        }

        if (committablePosition > initialPosition) {
            token.markProcessed(committablePosition);
        }
    }

    private void recordFailureInNewTransaction(String processingGroup, Failure failure) {
        transactionProvider.doInNewTransaction(() -> {
            var claimedToken = tokenStore.claimToken(processingGroup);
            if (claimedToken.isEmpty()) {
                LOGGER.debug("Token already claimed, skipping failure record: processingGroup={}", processingGroup);
                return;
            }

            var claimedSequence = failure.aggregateId()
                .map(aggregateId -> eventSequenceStore.claimAggregate(processingGroup, aggregateId))
                .orElseGet(() -> eventSequenceStore.claimGlobal(processingGroup));
            if (claimedSequence.isEmpty()) {
                LOGGER.debug("Event sequence already claimed, skipping failure record: processingGroup={}, aggregateId={}, globalPosition={}",
                    processingGroup, failure.aggregateId().orElse(null), failure.globalPosition());
                return;
            }

            var token = claimedToken.orElseThrow();
            var sequence = claimedSequence.orElseThrow();
            if (sequence.errorCount() >= processingConfig.getMaxRetries()) {
                LOGGER.error("Event abandoned (retries exhausted): processingGroup={}, aggregateId={}, globalPosition={}, maxRetries={}",
                    processingGroup, failure.aggregateId().orElse(null), failure.globalPosition(), processingConfig.getMaxRetries(), failure.exception());
                sequence.markAbandoned(failure.globalPosition(), failure.exception());

                if (token.lastProcessedPosition().orElse(0L) + 1 == failure.globalPosition()) {
                    token.markProcessed(failure.globalPosition());
                }
                return;
            }

            var nextErrorCount = sequence.errorCount() + 1;
            LOGGER.warn("Event processing failed: processingGroup={}, aggregateId={}, globalPosition={}, retry={}/{}",
                processingGroup, failure.aggregateId().orElse(null), failure.globalPosition(), nextErrorCount, processingConfig.getMaxRetries(), failure.exception());
            sequence.markFailed(failure.exception(), nextErrorCount);
        });
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
