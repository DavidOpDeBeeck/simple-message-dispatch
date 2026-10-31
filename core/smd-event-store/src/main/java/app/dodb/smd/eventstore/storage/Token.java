package app.dodb.smd.eventstore.processing;

import java.time.Instant;
import java.util.Optional;

public interface Token {

    Optional<Long> lastProcessedPosition();

    Optional<Instant> lastGapDetectedAt();

    void markProcessed(long globalPosition);

    void markGapDetected(long globalPosition);
}
