package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.sequence.EventSequence;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.sequence.EventSequenceStatus;
import app.dodb.smd.eventstore.sequence.EventSequenceStore;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.FAILED;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

public class InMemoryEventSequenceStore implements EventSequenceStore {

    private final ConcurrentMap<SequenceId, InMemoryEventSequence> sequences = new ConcurrentHashMap<>();

    @Override
    public Optional<EventSequence> claimGlobal(String processingGroup) {
        return claim(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSequence> claimAggregate(String processingGroup, String aggregateId) {
        return claim(processingGroup, Optional.of(requireNonNull(aggregateId)));
    }

    @Override
    public Optional<EventSequenceState> globalEventSequenceState(String processingGroup) {
        return eventSequenceState(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(String processingGroup, String aggregateId) {
        return eventSequenceState(processingGroup, Optional.of(requireNonNull(aggregateId)));
    }

    private Optional<EventSequence> claim(String processingGroup, Optional<String> aggregateId) {
        var sequenceId = new SequenceId(processingGroup, aggregateId);
        return Optional.of(sequences.computeIfAbsent(sequenceId, _ -> new InMemoryEventSequence()));
    }

    private Optional<EventSequenceState> eventSequenceState(String processingGroup, Optional<String> aggregateId) {
        var sequenceId = new SequenceId(processingGroup, aggregateId);
        var sequence = sequences.get(sequenceId);
        return sequence == null
            ? Optional.empty()
            : Optional.of(sequence.state(sequenceId));
    }

    private record SequenceId(String processingGroup, Optional<String> aggregateId) {

        SequenceId {
            requireNonNull(processingGroup);
            requireNonNull(aggregateId);
        }
    }

    private static class InMemoryEventSequence implements EventSequence {

        private EventSequenceStatus status = ACTIVE;
        private Long lastProcessedPosition;
        private int errorCount;
        private String lastErrorMessage;
        private Instant lastErrorAt;

        private synchronized EventSequenceState state(SequenceId sequenceId) {
            return new EventSequenceState(
                sequenceId.processingGroup(),
                sequenceId.aggregateId(),
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
