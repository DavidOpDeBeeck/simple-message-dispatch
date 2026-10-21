package app.dodb.smd.eventstore.deadline;

import java.time.Instant;

public interface ClaimedDeadline {

    SerializedDeadline deadline();

    int errorCount();

    void markFired();

    void markFailed(Exception exception, int errorCount, Instant failedAt, Instant nextAttemptAt);

    void markAbandoned(Exception exception, Instant abandonedAt);
}
