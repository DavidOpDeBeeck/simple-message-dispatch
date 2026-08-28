package app.dodb.smd.eventstore.sequence;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.FAILED;

public interface EventSubjectSequence {

    EventSubjectSequenceStatus status();

    int errorCount();

    Optional<Instant> lastErrorAt();

    Optional<Long> lastProcessedSequenceNumber();

    default boolean failed() {
        return status() == FAILED;
    }

    default boolean abandoned() {
        return status() == ABANDONED;
    }

    void markProcessed(long sequenceNumber);

    void markSkipped(long sequenceNumber);

    void markFailed(Exception exception, int errorCount);

    void markAbandoned(long sequenceNumber, Exception exception);

}
