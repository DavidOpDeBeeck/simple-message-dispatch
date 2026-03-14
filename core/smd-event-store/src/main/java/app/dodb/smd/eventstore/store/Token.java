package app.dodb.smd.eventstore.store;

import java.time.Instant;
import java.util.Optional;

public interface Token {

    Optional<Long> lastProcessedSequenceNumber();

    int errorCount();

    Instant lastErrorAt();

    Instant lastGapDetectedAt();

    void markProcessed(Long sequenceNumber);

    void markFailed(Long sequenceNumber, Exception exception);

    void markAbandoned(Long sequenceNumber, Exception exception);

    void markGapDetected(Long gapSequenceNumber);
}
