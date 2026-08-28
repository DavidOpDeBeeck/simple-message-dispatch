package app.dodb.smd.eventstore.sequence;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record EventSequenceState(String processingGroup,
                                 Optional<String> subjectId,
                                 EventSubjectSequenceStatus status,
                                 Optional<Long> lastProcessedSequenceNumber,
                                 int errorCount,
                                 Optional<String> lastErrorMessage,
                                 Optional<Instant> lastErrorAt) {

    public EventSequenceState {
        requireNonNull(processingGroup);
        requireNonNull(subjectId);
        requireNonNull(status);
        requireNonNull(lastProcessedSequenceNumber);
        requireNonNull(lastErrorMessage);
        requireNonNull(lastErrorAt);
    }
}
