package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.eventstore.RetryBackoffStrategy;
import app.dodb.smd.eventstore.serialization.DeadlineSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class DeadlineStore implements DeadlineScheduler, AutoCloseable {

    public static final String DUE_AT = "dueAt";
    public static final String ERROR_COUNT = "errorCount";

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadlineStore.class);

    private final TransactionProvider transactionProvider;
    private final TimeProvider timeProvider;
    private final MetadataFactory metadataFactory;
    private final DeadlineStorage deadlineStorage;
    private final DeadlineSerializer deadlineSerializer;
    private final ScheduledExecutorService scheduler;
    private final Duration initialDelay;
    private final Duration pollingDelay;
    private final int batchSize;
    private final int maxRetries;
    private final RetryBackoffStrategy retryBackoffStrategy;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    public DeadlineStore(DeadlineStoreConfig config) {
        requireNonNull(config);
        this.transactionProvider = config.getTransactionProvider();
        this.timeProvider = config.getTimeProvider();
        this.metadataFactory = new MetadataFactory(config.getPrincipalProvider(), config.getTimeProvider());
        this.deadlineStorage = config.getDeadlineStorage();
        this.deadlineSerializer = config.getDeadlineSerializer();
        this.scheduler = config.getScheduler();
        this.initialDelay = config.getInitialDelay();
        this.pollingDelay = config.getPollingDelay();
        this.batchSize = config.getBatchSize();
        this.maxRetries = config.getMaxRetries();
        this.retryBackoffStrategy = config.getRetryBackoffStrategy();
    }

    @Override
    public DeadlineId schedule(Command<?> command, Instant dueAt) {
        var deadline = serialize(command, dueAt);
        transactionProvider.doInTransaction(() -> deadlineStorage.store(deadline));
        LOGGER.debug("Deadline scheduled: deadlineId={}, commandType={}, dueAt={}", deadline.deadlineId().value(), deadline.commandType(), dueAt);
        return deadline.deadlineId();
    }

    @Override
    public DeadlineId schedule(Command<?> command, Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Deadline delay must not be negative: command=" + command.getClass().getName() + ", delay=" + delay);
        }
        return schedule(command, timeProvider.now().plus(delay));
    }

    @Override
    public boolean cancel(DeadlineId deadlineId) {
        boolean cancelled = transactionProvider.doInTransaction(() -> deadlineStorage.delete(deadlineId));
        LOGGER.debug("Deadline cancel: deadlineId={}, cancelled={}", deadlineId.value(), cancelled);
        return cancelled;
    }

    public Optional<DeadlineState> deadlineState(DeadlineId deadlineId) {
        return deadlineStorage.deadlineState(deadlineId);
    }

    public DeadlineSubscription subscribe(CommandGateway commandGateway) {
        requireNonNull(commandGateway);
        if (!subscribed.compareAndSet(false, true)) {
            throw new IllegalStateException("Deadline store already has an open subscription");
        }

        var firing = scheduler.scheduleWithFixedDelay(
            () -> fireDueDeadlines(commandGateway),
            initialDelay.toMillis(),
            pollingDelay.toMillis(),
            MILLISECONDS
        );
        LOGGER.info("Deadline firing started: pollingDelay={}, batchSize={}", pollingDelay, batchSize);
        return new FiringSubscription(firing);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (scheduler.awaitTermination(30, SECONDS)) {
                return;
            }
            scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    int fireDueDeadlines(CommandGateway commandGateway) {
        var attempts = 0;
        try {
            while (attempts < batchSize && fireNextDueDeadline(commandGateway)) {
                attempts++;
            }
        } catch (Exception exception) {
            LOGGER.error("Deadline polling error", exception);
        }
        return attempts;
    }

    private boolean fireNextDueDeadline(CommandGateway commandGateway) {
        // Set inside the firing transaction so that a failure raised at commit is still recorded for the claimed deadline.
        var claimedDeadlineId = new AtomicReference<DeadlineId>();
        try {
            return transactionProvider.doInNewTransaction(() -> {
                var now = timeProvider.now();
                var claimedDeadline = deadlineStorage.claimDue(now);
                if (claimedDeadline.isEmpty()) {
                    return false;
                }

                var deadline = claimedDeadline.get();
                claimedDeadlineId.set(deadline.deadline().deadlineId());
                fire(commandGateway, deadline, now);
                deadline.markFired();
                return true;
            });
        } catch (Exception exception) {
            var deadlineId = claimedDeadlineId.get();
            if (deadlineId == null) {
                throw exception;
            }
            recordFailure(deadlineId, exception);
            return true;
        }
    }

    private void fire(CommandGateway commandGateway, ClaimedDeadline claimedDeadline, Instant now) {
        var deadline = claimedDeadline.deadline();
        var commandMessage = deadlineSerializer.deserialize(deadline);
        var attemptMetadata = new Metadata(null, now, null, Map.of(
            DUE_AT, deadline.dueAt().toString(),
            ERROR_COUNT, String.valueOf(claimedDeadline.errorCount())
        ));

        commandGateway.send(commandMessage.andMetadata(attemptMetadata));
        LOGGER.debug("Deadline fired: deadlineId={}, commandType={}, dueAt={}", deadline.deadlineId().value(), deadline.commandType(), deadline.dueAt());
    }

    private void recordFailure(DeadlineId deadlineId, Exception exception) {
        try {
            transactionProvider.doInNewTransaction(() -> {
                var claimedDeadline = deadlineStorage.claim(deadlineId);
                if (claimedDeadline.isEmpty()) {
                    LOGGER.debug("Deadline cancelled or claimed, skipping failure record: deadlineId={}", deadlineId.value());
                    return;
                }

                var deadline = claimedDeadline.get();
                var commandType = deadline.deadline().commandType();
                var now = timeProvider.now();
                if (deadline.errorCount() >= maxRetries) {
                    LOGGER.error("Deadline abandoned (retries exhausted): deadlineId={}, commandType={}, maxRetries={}",
                        deadlineId.value(), commandType, maxRetries, exception);
                    deadline.markAbandoned(exception, now);
                    return;
                }

                var errorCount = deadline.errorCount() + 1;
                var nextAttemptAt = now.plus(retryBackoffStrategy.calculateDelay(errorCount - 1));
                LOGGER.warn("Deadline failed: deadlineId={}, commandType={}, retry={}/{}, nextAttemptAt={}",
                    deadlineId.value(), commandType, errorCount, maxRetries, nextAttemptAt, exception);
                deadline.markFailed(exception, errorCount, now, nextAttemptAt);
            });
        } catch (Exception recordingFailure) {
            exception.addSuppressed(recordingFailure);
            LOGGER.error("Failed to record deadline failure: deadlineId={}", deadlineId.value(), exception);
        }
    }

    // Closing stops later polls without waiting for a running one; the executor is only shut down by close() of the store.
    private class FiringSubscription implements DeadlineSubscription {

        private final ScheduledFuture<?> firing;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FiringSubscription(ScheduledFuture<?> firing) {
            this.firing = requireNonNull(firing);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            firing.cancel(false);
            subscribed.set(false);
            LOGGER.info("Deadline firing stopped");
        }
    }

    private <R, C extends Command<R>> SerializedDeadline serialize(C command, Instant dueAt) {
        var deadlineId = DeadlineId.generate();
        var metadata = metadataFactory.createScope().metadataSupplier().get();
        var commandMessage = new CommandMessage<R, C>(new MessageId(deadlineId.value()), command, metadata);
        return deadlineSerializer.serialize(commandMessage, dueAt);
    }
}
