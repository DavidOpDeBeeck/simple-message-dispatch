package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.RetryBackoffStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;

class OutboxRelay implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_SENDS_PER_POLL = 100;

    private final String outbox;
    private final TransportSink sink;
    private final OutboxStorage storage;
    private final TransactionProvider transactionProvider;
    private final RetryBackoffStrategy retryBackoffStrategy;

    OutboxRelay(String outbox, TransportSink sink, OutboxStorage storage, DurableDeliveryConfig config) {
        this.outbox = requireNonNull(outbox);
        this.sink = requireNonNull(sink);
        this.storage = requireNonNull(storage);
        this.transactionProvider = config.getTransactionProvider();
        this.retryBackoffStrategy = config.getRetryBackoffStrategy();
    }

    @Override
    public void run() {
        try {
            for (int sends = 0; sends < MAX_SENDS_PER_POLL && !Thread.currentThread().isInterrupted(); sends++) {
                if (!sendNext()) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Outbox polling failed: outbox={}", outbox, e);
        }
    }

    // The claimed row stays locked while the transport sends, and is marked sent in the same transaction.
    // A crash after the send but before the commit sends the event again; the inbox drops the duplicate.
    private boolean sendNext() {
        try {
            return transactionProvider.doInNewTransaction(() -> storage.claimNext(outbox)
                .map(entry -> {
                    send(entry);
                    storage.markSent(outbox, entry.id());
                    if (entry.attempts() > 0) {
                        LOGGER.info("Outbox send recovered: outbox={}, messageId={}, attempts={}",
                            outbox, entry.event().event().messageId().value(), entry.attempts());
                    }
                    return true;
                })
                .orElse(false));
        } catch (SendFailure failure) {
            var entry = failure.entry;
            var retryDelay = retryBackoffStrategy.calculateDelay(entry.attempts());
            transactionProvider.doInNewTransaction(() -> storage.recordFailure(outbox, entry, retryDelay, failure.cause()));
            LOGGER.warn("Outbox send failed, retrying in {}: outbox={}, messageId={}, subjectId={}, attempt={}",
                retryDelay, outbox, entry.event().event().messageId().value(), entry.event().event().subjectId().orElse(null), entry.attempts() + 1,
                failure.cause());
            return true;
        }
    }

    private void send(OutboxEntry entry) {
        try {
            sink.send(entry.event());
        } catch (RuntimeException e) {
            throw new SendFailure(entry, e);
        }
    }

    private static class SendFailure extends RuntimeException {

        private final OutboxEntry entry;

        private SendFailure(OutboxEntry entry, RuntimeException cause) {
            super(cause);
            this.entry = entry;
        }

        private Exception cause() {
            return (Exception) getCause();
        }
    }
}
