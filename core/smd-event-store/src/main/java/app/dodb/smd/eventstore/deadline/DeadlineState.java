package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.deadline.DeadlineId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record DeadlineState(DeadlineId deadlineId,
                            String commandType,
                            Instant dueAt,
                            Instant nextAttemptAt,
                            DeadlineStatus status,
                            int errorCount,
                            Optional<String> lastErrorMessage,
                            Optional<Instant> lastErrorAt) {

    public DeadlineState {
        requireNonNull(deadlineId);
        requireNonNull(commandType);
        requireNonNull(dueAt);
        requireNonNull(nextAttemptAt);
        requireNonNull(status);
        requireNonNull(lastErrorMessage);
        requireNonNull(lastErrorAt);
    }
}
