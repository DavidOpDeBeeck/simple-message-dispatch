package app.dodb.smd.eventstore.storage;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record TokenState(String processingGroup,
                         Optional<Long> lastProcessedSequenceNumber,
                         Optional<Instant> lastGapDetectedAt,
                         Optional<Long> gapSequenceNumber) {

    public TokenState {
        requireNonNull(processingGroup);
        requireNonNull(lastProcessedSequenceNumber);
        requireNonNull(lastGapDetectedAt);
        requireNonNull(gapSequenceNumber);
    }
}
