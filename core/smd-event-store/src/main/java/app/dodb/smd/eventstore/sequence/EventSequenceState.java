package app.dodb.smd.eventstore.sequence;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record EventSequenceState(String processingGroup,
                                 Optional<String> aggregateId,
                                 EventSequenceStatus status,
                                 Optional<Long> lastProcessedPosition,
                                 int errorCount,
                                 Optional<String> lastErrorMessage,
                                 Optional<Instant> lastErrorAt) {

    public EventSequenceState {
        requireNonNull(processingGroup);
        requireNonNull(aggregateId);
        requireNonNull(status);
        requireNonNull(lastProcessedPosition);
        requireNonNull(lastErrorMessage);
        requireNonNull(lastErrorAt);
    }
}
