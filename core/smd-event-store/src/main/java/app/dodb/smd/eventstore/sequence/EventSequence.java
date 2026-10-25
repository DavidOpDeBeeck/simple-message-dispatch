package app.dodb.smd.eventstore.sequence;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.FAILED;

public interface EventSequence {

    EventSequenceStatus status();

    int errorCount();

    Optional<Instant> lastErrorAt();

    Optional<Long> lastProcessedPosition();

    default boolean failed() {
        return status() == FAILED;
    }

    default boolean abandoned() {
        return status() == ABANDONED;
    }

    void markProcessed(long globalPosition);

    void markSkipped(long globalPosition);

    void markFailed(Exception exception, int errorCount);

    void markAbandoned(long globalPosition, Exception exception);

}
