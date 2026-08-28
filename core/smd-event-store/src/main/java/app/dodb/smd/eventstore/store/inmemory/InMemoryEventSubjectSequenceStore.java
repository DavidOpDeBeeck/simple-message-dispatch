package app.dodb.smd.eventstore.store.inmemory;

import app.dodb.smd.eventstore.sequence.EventSubjectSequence;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.sequence.EventSequenceState;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.FAILED;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

public class InMemoryEventSubjectSequenceStore implements EventSubjectSequenceStore {

    private final ConcurrentMap<SequenceId, InMemoryEventSubjectSequence> sequences = new ConcurrentHashMap<>();

    @Override
    public Optional<EventSubjectSequence> claimGlobal(String processingGroup) {
        return claim(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSubjectSequence> claimSubject(String processingGroup, String subjectId) {
        return claim(processingGroup, Optional.of(requireNonNull(subjectId)));
    }

    @Override
    public Optional<EventSequenceState> globalEventSequenceState(String processingGroup) {
        return eventSequenceState(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(String processingGroup, String subjectId) {
        return eventSequenceState(processingGroup, Optional.of(requireNonNull(subjectId)));
    }

    private Optional<EventSubjectSequence> claim(String processingGroup, Optional<String> subjectId) {
        var sequenceId = new SequenceId(processingGroup, subjectId);
        return Optional.of(sequences.computeIfAbsent(sequenceId, _ -> new InMemoryEventSubjectSequence()));
    }

    private Optional<EventSequenceState> eventSequenceState(String processingGroup, Optional<String> subjectId) {
        var sequenceId = new SequenceId(processingGroup, subjectId);
        var sequence = sequences.get(sequenceId);
        return sequence == null
            ? Optional.empty()
            : Optional.of(sequence.state(sequenceId));
    }

    private record SequenceId(String processingGroup, Optional<String> subjectId) {

        SequenceId {
            requireNonNull(processingGroup);
            requireNonNull(subjectId);
        }
    }

    private static class InMemoryEventSubjectSequence implements EventSubjectSequence {

        private EventSubjectSequenceStatus status = ACTIVE;
        private Long lastProcessedSequenceNumber;
        private int errorCount;
        private String lastErrorMessage;
        private Instant lastErrorAt;

        private synchronized EventSequenceState state(SequenceId sequenceId) {
            return new EventSequenceState(
                sequenceId.processingGroup(),
                sequenceId.subjectId(),
                status,
                Optional.ofNullable(lastProcessedSequenceNumber),
                errorCount,
                Optional.ofNullable(lastErrorMessage),
                Optional.ofNullable(lastErrorAt)
            );
        }

        @Override
        public synchronized EventSubjectSequenceStatus status() {
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
        public synchronized Optional<Long> lastProcessedSequenceNumber() {
            return Optional.ofNullable(lastProcessedSequenceNumber);
        }

        @Override
        public synchronized void markProcessed(long sequenceNumber) {
            status = ACTIVE;
            lastProcessedSequenceNumber = highestSequenceNumber(sequenceNumber);
            errorCount = 0;
            lastErrorMessage = null;
            lastErrorAt = null;
        }

        @Override
        public synchronized void markSkipped(long sequenceNumber) {
            lastProcessedSequenceNumber = highestSequenceNumber(sequenceNumber);
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
        public synchronized void markAbandoned(long sequenceNumber, Exception exception) {
            requireNonNull(exception);
            status = ABANDONED;
            lastProcessedSequenceNumber = highestSequenceNumber(sequenceNumber);
            errorCount = 0;
            lastErrorMessage = getStackTraceAsString(exception);
            lastErrorAt = Instant.now();
        }

        private long highestSequenceNumber(long sequenceNumber) {
            return lastProcessedSequenceNumber == null
                ? sequenceNumber
                : Math.max(lastProcessedSequenceNumber, sequenceNumber);
        }
    }
}
