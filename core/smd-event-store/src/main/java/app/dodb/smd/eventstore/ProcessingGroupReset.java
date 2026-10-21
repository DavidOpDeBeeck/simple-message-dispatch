package app.dodb.smd.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.ResetResult.Reset;
import app.dodb.smd.eventstore.ResetResult.TokenClaimed;
import app.dodb.smd.eventstore.ResetResult.UnknownProcessingGroup;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.storage.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

class ProcessingGroupReset {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingGroupReset.class);

    private final TransactionProvider transactionProvider;
    private final TokenStore tokenStore;
    private final EventSubjectSequenceStore eventSubjectSequenceStore;
    private final Duration resetLockTimeout;
    private final Predicate<String> subscribedProcessingGroup;

    ProcessingGroupReset(EventStoreConfig config, Predicate<String> subscribedProcessingGroup) {
        this.transactionProvider = requireNonNull(config.getTransactionProvider());
        this.tokenStore = requireNonNull(config.getTokenStore());
        this.eventSubjectSequenceStore = requireNonNull(config.getEventSequenceStore());
        this.resetLockTimeout = requireNonNull(config.getProcessingConfig().getResetLockTimeout());
        this.subscribedProcessingGroup = requireNonNull(subscribedProcessingGroup);
    }

    ResetResult reset(String processingGroup, Runnable resetAction) {
        requireNonNull(processingGroup);
        requireNonNull(resetAction);
        if (tokenStore.tokenState(processingGroup).isEmpty() && !subscribedProcessingGroup.test(processingGroup)) {
            LOGGER.warn("Reset skipped, processing group has no token and no subscription to this event store: processingGroup={}", processingGroup);
            return new UnknownProcessingGroup(processingGroup);
        }

        var result = transactionProvider.doInNewTransaction(() -> resetClaimedToken(processingGroup, resetAction));
        if (result instanceof Reset reset) {
            LOGGER.info("Processing group reset to start: processingGroup={}, previousPosition={}, replayUntil={}",
                processingGroup, reset.previousPosition().orElse(null), reset.replayUntil().orElse(null));
        }
        if (result instanceof TokenClaimed) {
            LOGGER.warn("Reset skipped, token still claimed after reset lock timeout: processingGroup={}, resetLockTimeout={}",
                processingGroup, resetLockTimeout);
        }
        return result;
    }

    private ResetResult resetClaimedToken(String processingGroup, Runnable resetAction) {
        var claimedToken = tokenStore.claimTokenForReset(processingGroup, resetLockTimeout);
        if (claimedToken.isEmpty()) {
            return new TokenClaimed(processingGroup);
        }

        var token = claimedToken.get();
        var previousPosition = token.lastProcessedSequenceNumber();
        var replayUntil = Stream.of(previousPosition, token.replayUntilSequenceNumber())
            .flatMap(Optional::stream)
            .filter(position -> position > 0)
            .max(Long::compare);

        // The action runs first so that its failure leaves even non-transactional stores untouched.
        resetAction.run();
        eventSubjectSequenceStore.deleteSequences(processingGroup);
        token.markReset(replayUntil);
        return new Reset(processingGroup, previousPosition, replayUntil);
    }
}
