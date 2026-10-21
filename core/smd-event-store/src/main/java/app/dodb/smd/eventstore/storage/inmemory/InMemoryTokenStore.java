package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.storage.Token;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.eventstore.storage.TokenStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.requireNonNull;

public class InMemoryTokenStore implements TokenStore {

    private final ConcurrentMap<String, InMemoryToken> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTokenStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTokenStore(Clock clock) {
        this.clock = requireNonNull(clock);
    }

    @Override
    public Optional<Token> claimToken(String processingGroup) {
        return Optional.of(tokens.computeIfAbsent(processingGroup, _ -> new InMemoryToken(clock)));
    }

    @Override
    public Optional<Token> claimTokenForReset(String processingGroup, Duration lockTimeout) {
        return claimToken(processingGroup);
    }

    @Override
    public Optional<TokenState> tokenState(String processingGroup) {
        var token = tokens.get(processingGroup);
        return token == null
            ? Optional.empty()
            : Optional.of(token.state(processingGroup));
    }

    private static class InMemoryToken implements Token {

        private final Clock clock;
        private Long lastProcessedSequenceNumber;
        private Instant lastGapDetectedAt;
        private Long gapSequenceNumber;
        private Long replayUntilSequenceNumber;

        private InMemoryToken(Clock clock) {
            this.clock = clock;
        }

        private synchronized TokenState state(String processingGroup) {
            return new TokenState(
                processingGroup,
                Optional.ofNullable(lastProcessedSequenceNumber),
                Optional.ofNullable(lastGapDetectedAt),
                Optional.ofNullable(gapSequenceNumber),
                Optional.ofNullable(replayUntilSequenceNumber)
            );
        }

        @Override
        public synchronized Optional<Long> lastProcessedSequenceNumber() {
            return Optional.ofNullable(lastProcessedSequenceNumber);
        }

        @Override
        public synchronized Optional<Instant> lastGapDetectedAt() {
            return Optional.ofNullable(lastGapDetectedAt);
        }

        @Override
        public synchronized Optional<Long> replayUntilSequenceNumber() {
            return Optional.ofNullable(replayUntilSequenceNumber);
        }

        @Override
        public synchronized void markProcessed(long sequenceNumber) {
            if (lastProcessedSequenceNumber != null && sequenceNumber < lastProcessedSequenceNumber) {
                return;
            }

            lastProcessedSequenceNumber = sequenceNumber;
            if (gapSequenceNumber != null && sequenceNumber >= gapSequenceNumber) {
                lastGapDetectedAt = null;
                gapSequenceNumber = null;
            }
            if (replayUntilSequenceNumber != null && sequenceNumber >= replayUntilSequenceNumber) {
                replayUntilSequenceNumber = null;
            }
        }

        @Override
        public synchronized void markGapDetected(long sequenceNumber) {
            lastGapDetectedAt = clock.instant();
            gapSequenceNumber = sequenceNumber;
        }

        @Override
        public synchronized void markReset(Optional<Long> replayUntilSequenceNumber) {
            lastProcessedSequenceNumber = 0L;
            lastGapDetectedAt = null;
            gapSequenceNumber = null;
            this.replayUntilSequenceNumber = replayUntilSequenceNumber.orElse(null);
        }
    }
}
