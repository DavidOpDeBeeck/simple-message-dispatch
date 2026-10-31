package app.dodb.smd.eventstore.processing;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record EventSequenceState(String processingGroup,
                                 EventSequenceId sequenceId,
                                 EventSequenceStatus status,
                                 Optional<Long> lastProcessedPosition,
                                 int errorCount,
                                 Optional<String> lastErrorMessage,
                                 Optional<Instant> lastErrorAt) {

    public EventSequenceState {
        requireNonNull(processingGroup);
        requireNonNull(sequenceId);
        requireNonNull(status);
        requireNonNull(lastProcessedPosition);
        requireNonNull(lastErrorMessage);
        requireNonNull(lastErrorAt);
    }
}
