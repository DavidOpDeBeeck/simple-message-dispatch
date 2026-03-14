package app.dodb.smd.eventstore.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.EventBusInterceptorChain;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.event.channel.EventChannelListener;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig.ProcessingConfig;
import app.dodb.smd.eventstore.store.EventSerializer;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.Token;
import app.dodb.smd.eventstore.store.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.eventstore.channel.EventStoreChannelConfig.SchedulingConfig;
import static java.time.Duration.between;
import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class EventStoreChannel implements EventChannel, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreChannel.class);

    private final TransactionProvider transactionProvider;
    private final TokenStore tokenStore;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;
    private final List<EventBusInterceptor> interceptors;
    private final SchedulingConfig schedulingConfig;
    private final ProcessingConfig processingConfig;

    public EventStoreChannel(EventStoreChannelConfig config) {
        this.transactionProvider = config.getTransactionProvider();
        this.tokenStore = config.getTokenStore();
        this.eventStorage = config.getEventStorage();
        this.eventSerializer = config.getEventSerializer();
        this.interceptors = config.getInterceptors();
        this.schedulingConfig = config.getSchedulingConfig();
        this.processingConfig = config.getProcessingConfig();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        transactionProvider.defer(() -> eventStorage.store(eventSerializer.serialize(eventMessage)));
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        if (!schedulingConfig.isEnabled()) {
            LOGGER.info("Event store polling disabled: processingGroup={}", listener.processingGroup());
            return;
        }
        var scheduler = schedulingConfig.getScheduler();
        scheduler.scheduleWithFixedDelay(
            () -> pollAndProcess(listener),
            schedulingConfig.getInitialDelay().toMillis(),
            schedulingConfig.getPollingDelay().toMillis(),
            MILLISECONDS
        );
    }

    private void pollAndProcess(EventChannelListener listener) {
        var processingGroup = listener.processingGroup();
        try {
            var token = tokenStore.getToken(processingGroup);
            var newBatchSize = processingConfig.getBatchSize();

            do {
                int finalNewBatchSize = newBatchSize;
                newBatchSize = transactionProvider.doInNewTransaction(() ->
                    switch (processBatch(listener, token, processingConfig, finalNewBatchSize)) {
                        case NothingToProcess _ -> 0;
                        case GapDetected(var expectedNextSeq) -> {
                            transactionProvider.doInNewTransaction(() -> token.markGapDetected(expectedNextSeq));
                            yield 0;
                        }
                        case Failed(var sequenceNumber, var exception) -> {
                            transactionProvider.doInNewTransaction(() -> token.markFailed(sequenceNumber, exception));
                            yield 0;
                        }
                        case Abandoned(var sequenceNumber, var exception) -> {
                            transactionProvider.doInNewTransaction(() -> token.markAbandoned(sequenceNumber, exception));
                            yield 0;
                        }
                        // Transaction is not tainted, we can safely mark the items as processed
                        case GapDetectedMidBatch(var sequenceNumber, var retryBatchSize) -> {
                            token.markProcessed(sequenceNumber);
                            yield retryBatchSize;
                        }
                        // Transaction will be tainted, we cannot safely mark the items as processed
                        case FailedMidBatch(var sequenceNumber, var retryBatchSize) -> retryBatchSize;
                        case BatchSucceeded(var sequenceNumber) -> {
                            token.markProcessed(sequenceNumber);
                            yield processingConfig.getBatchSize();
                        }
                    });
            } while (newBatchSize != 0);
        } catch (Exception e) {
            LOGGER.error("Polling error: processingGroup={}, error={}", processingGroup, e.getMessage(), e);
        }
    }

    private Result processBatch(EventChannelListener listener, Token token, ProcessingConfig processingConfig, int batchSize) {
        var processingGroup = listener.processingGroup();
        var currentErrorCount = token.errorCount();

        if (currentErrorCount > processingConfig.getMaxRetries()) {
            LOGGER.error("Processing abandoned (retries exhausted): processingGroup={}, errorCount={}, maxRetries={}",
                processingGroup, currentErrorCount, processingConfig.getMaxRetries());
            return new NothingToProcess();
        }

        if (currentErrorCount > 0) {
            var lastErrorAt = token.lastErrorAt();
            var backoffDelay = processingConfig.getRetryBackoffStrategy().calculateDelay(currentErrorCount - 1);
            var nextRetryTime = lastErrorAt.plus(backoffDelay);
            var currentTime = now();

            if (currentTime.isBefore(nextRetryTime)) {
                var remainingDelay = between(currentTime, nextRetryTime);
                LOGGER.debug("Backoff active: processingGroup={}, remainingDelay={}ms",
                    processingGroup, remainingDelay.toMillis());
                return new NothingToProcess();
            }
        }

        long lastProcessedSeq = token.lastProcessedSequenceNumber().orElse(0L);
        try (var cursor = eventStorage.load(lastProcessedSeq, batchSize)) {
            if (!cursor.hasNext()) {
                LOGGER.debug("No events to process: processingGroup={}, lastProcessedSequence={}", processingGroup, lastProcessedSeq);
                return new NothingToProcess();
            }
            var chain = EventBusInterceptorChain.create(listener::on, interceptors);
            long lastProcessedInBatch = lastProcessedSeq;
            while (cursor.hasNext()) {
                var eventToProcess = cursor.next();
                var sequenceToProcess = eventToProcess.sequenceNumber();
                var isFirstItem = lastProcessedInBatch == lastProcessedSeq;

                if (sequenceToProcess > lastProcessedInBatch + 1) {
                    if (!isFirstItem) {
                        LOGGER.debug("Gap detected mid-batch, retrying without gap: processingGroup={}, expectedSequence={}, actualSequence={}",
                            processingGroup, lastProcessedInBatch + 1, sequenceToProcess);
                        return new GapDetectedMidBatch(lastProcessedInBatch, (int) (lastProcessedInBatch - lastProcessedSeq));
                    }

                    var gapDetectedAt = token.lastGapDetectedAt();
                    if (gapDetectedAt == null) {
                        LOGGER.warn("Gap detected: processingGroup={}, expectedSequence={}, actualSequence={}",
                            processingGroup, lastProcessedSeq + 1, sequenceToProcess);
                        return new GapDetected(lastProcessedSeq + 1);
                    }

                    var currentTime = now();
                    var gapAge = between(gapDetectedAt, currentTime);
                    if (gapAge.compareTo(processingConfig.getGapTimeout()) < 0) {
                        LOGGER.debug("Gap still present: processingGroup={}, age={}ms, timeout={}ms",
                            processingGroup, gapAge.toMillis(), processingConfig.getGapTimeout().toMillis());
                        return new NothingToProcess();
                    }

                    LOGGER.error("Gap timeout expired, skipping: processingGroup={}, fromSequence={}, toSequence={}",
                        processingGroup, lastProcessedSeq + 1, sequenceToProcess - 1);
                }

                var eventMessage = eventSerializer.deserialize(eventToProcess)
                    .andMetadata(new Metadata(null, null, null, Map.of(
                        "sequenceNumber", String.valueOf(sequenceToProcess),
                        "errorCount", String.valueOf(currentErrorCount)
                    )));

                try {
                    chain.proceed(eventMessage);
                    lastProcessedInBatch = sequenceToProcess;
                    LOGGER.debug("Event processed: processingGroup={}, sequenceNumber={}, messageId={}",
                        processingGroup, sequenceToProcess, eventToProcess.messageId());
                } catch (Exception e) {
                    if (!isFirstItem) {
                        LOGGER.warn("Event processing failed mid-batch, retrying with {} items: processingGroup={}, sequenceNumber={}, messageId={}, retry={}/{}, error={}",
                            lastProcessedInBatch - lastProcessedSeq, processingGroup, sequenceToProcess, eventToProcess.messageId(), currentErrorCount + 1, processingConfig.getMaxRetries(), e.getMessage(), e);
                        return new FailedMidBatch(lastProcessedInBatch, (int) (lastProcessedInBatch - lastProcessedSeq));
                    }
                    if (currentErrorCount >= processingConfig.getMaxRetries()) {
                        LOGGER.error("Event abandoned (retries exhausted): processingGroup={}, sequenceNumber={}, messageId={}, maxRetries={}, error={}",
                            processingGroup, sequenceToProcess, eventToProcess.messageId(), processingConfig.getMaxRetries(), e.getMessage(), e);
                        return new Abandoned(sequenceToProcess, e);
                    }
                    LOGGER.warn("Event processing failed: processingGroup={}, sequenceNumber={}, messageId={}, retry={}/{}, error={}",
                        processingGroup, sequenceToProcess, eventToProcess.messageId(), currentErrorCount + 1, processingConfig.getMaxRetries(), e.getMessage(), e);
                    return new Failed(sequenceToProcess, e);
                }
            }
            return new BatchSucceeded(lastProcessedInBatch);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService scheduler = schedulingConfig.getScheduler();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    sealed interface Result permits GapDetectedMidBatch, GapDetected, Abandoned, Failed, FailedMidBatch, NothingToProcess, BatchSucceeded {
    }

    record NothingToProcess() implements Result {
    }

    record GapDetected(long expectedSeq) implements Result {
    }

    record Failed(long sequenceNumber, Exception exception) implements Result {
    }

    record Abandoned(long sequenceNumber, Exception exception) implements Result {
    }

    record GapDetectedMidBatch(long sequenceNumber, int processedCount) implements Result {
    }

    record FailedMidBatch(long sequenceNumber, int processedCount) implements Result {
    }

    record BatchSucceeded(long sequenceNumber) implements Result {
    }
}
