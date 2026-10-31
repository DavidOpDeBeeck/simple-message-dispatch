package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.processing.EventSequence;
import app.dodb.smd.eventstore.processing.EventSequenceId;
import app.dodb.smd.eventstore.processing.EventSequenceState;
import app.dodb.smd.eventstore.processing.EventSequenceStatus;
import app.dodb.smd.eventstore.processing.EventSequenceStore;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static app.dodb.smd.eventstore.processing.EventSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.processing.EventSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.processing.EventSequenceStatus.FAILED;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

public class InMemoryEventSequenceStore implements EventSequenceStore {

    private final ConcurrentMap<SequenceKey, InMemoryEventSequence> sequences = new ConcurrentHashMap<>();

    @Override
    public Optional<EventSequence> claim(String processingGroup, EventSequenceId sequenceId) {
        var key = new SequenceKey(processingGroup, sequenceId);
        return Optional.of(sequences.computeIfAbsent(key, _ -> new InMemoryEventSequence()));
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(String processingGroup, EventSequenceId sequenceId) {
        var key = new SequenceKey(processingGroup, sequenceId);
        var sequence = sequences.get(key);
        return sequence == null
            ? Optional.empty()
            : Optional.of(sequence.state(key));
    }

    private record SequenceKey(String processingGroup, EventSequenceId sequenceId) {

        SequenceKey {
            requireNonNull(processingGroup);
            requireNonNull(sequenceId);
        }
    }

    private static class InMemoryEventSequence implements EventSequence {

        private EventSequenceStatus status = ACTIVE;
        private Long lastProcessedPosition;
        private int errorCount;
        private String lastErrorMessage;
        private Instant lastErrorAt;

        private synchronized EventSequenceState state(SequenceKey key) {
            return new EventSequenceState(
                key.processingGroup(),
                key.sequenceId(),
                status,
                Optional.ofNullable(lastProcessedPosition),
                errorCount,
                Optional.ofNullable(lastErrorMessage),
                Optional.ofNullable(lastErrorAt)
            );
        }

        @Override
        public synchronized EventSequenceStatus status() {
            return status;
        }

        @Override
        public synchronized int errorCount() {
            return errorCount;
        }

        @Override
        public synchronized Optional<Instant> lastErrorAt() {
            return Optional.ofNullable(lastErrorAt);
        }

        @Override
        public synchronized Optional<Long> lastProcessedPosition() {
            return Optional.ofNullable(lastProcessedPosition);
        }

        @Override
        public synchronized void markProcessed(long globalPosition) {
            status = ACTIVE;
            lastProcessedPosition = highestPosition(globalPosition);
            errorCount = 0;
            lastErrorMessage = null;
            lastErrorAt = null;
        }

        @Override
        public synchronized void markSkipped(long globalPosition) {
            lastProcessedPosition = highestPosition(globalPosition);
        }

        @Override
        public synchronized void markFailed(Exception exception,
                                            int errorCount) {
            requireNonNull(exception);
            status = FAILED;
            this.errorCount = errorCount;
            lastErrorMessage = getStackTraceAsString(exception);
            lastErrorAt = Instant.now();
        }

        @Override
        public synchronized void markAbandoned(long globalPosition, Exception exception) {
            requireNonNull(exception);
            status = ABANDONED;
            lastProcessedPosition = highestPosition(globalPosition);
            errorCount = 0;
            lastErrorMessage = getStackTraceAsString(exception);
            lastErrorAt = Instant.now();
        }

        private long highestPosition(long globalPosition) {
            return lastProcessedPosition == null
                ? globalPosition
                : Math.max(lastProcessedPosition, globalPosition);
        }
    }
}
