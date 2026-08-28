package app.dodb.smd.eventstore.store.inmemory;

import app.dodb.smd.eventstore.store.Token;
import app.dodb.smd.eventstore.store.TokenState;
import app.dodb.smd.eventstore.store.TokenStore;

import java.time.Clock;
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

        private InMemoryToken(Clock clock) {
            this.clock = clock;
        }

        private synchronized TokenState state(String processingGroup) {
            return new TokenState(
                processingGroup,
                Optional.ofNullable(lastProcessedSequenceNumber),
                Optional.ofNullable(lastGapDetectedAt),
                Optional.ofNullable(gapSequenceNumber)
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
        public synchronized void markProcessed(long sequenceNumber) {
            if (lastProcessedSequenceNumber != null && sequenceNumber < lastProcessedSequenceNumber) {
                return;
            }

            lastProcessedSequenceNumber = sequenceNumber;
            if (gapSequenceNumber != null && sequenceNumber >= gapSequenceNumber) {
                lastGapDetectedAt = null;
                gapSequenceNumber = null;
            }
        }

        @Override
        public synchronized void markGapDetected(long sequenceNumber) {
            lastGapDetectedAt = clock.instant();
            gapSequenceNumber = sequenceNumber;
        }
    }
}
