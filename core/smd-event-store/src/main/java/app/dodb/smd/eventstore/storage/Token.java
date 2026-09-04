package app.dodb.smd.eventstore.storage;

import java.time.Instant;
import java.util.Optional;

public interface Token {

    Optional<Long> lastProcessedSequenceNumber();

    Optional<Instant> lastGapDetectedAt();

    void markProcessed(long sequenceNumber);

    void markGapDetected(long sequenceNumber);
}
