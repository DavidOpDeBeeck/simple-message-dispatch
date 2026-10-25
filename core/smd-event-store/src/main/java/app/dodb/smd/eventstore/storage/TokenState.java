package app.dodb.smd.eventstore.storage;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record TokenState(String processingGroup,
                         Optional<Long> lastProcessedPosition,
                         Optional<Instant> lastGapDetectedAt,
                         Optional<Long> gapPosition) {

    public TokenState {
        requireNonNull(processingGroup);
        requireNonNull(lastProcessedPosition);
        requireNonNull(lastGapDetectedAt);
        requireNonNull(gapPosition);
    }
}
