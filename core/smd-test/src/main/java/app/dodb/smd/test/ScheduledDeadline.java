package app.dodb.smd.test;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.deadline.DeadlineId;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public record ScheduledDeadline(DeadlineId deadlineId, Instant dueAt, Command<?> command) {

    public ScheduledDeadline {
        requireNonNull(deadlineId);
        requireNonNull(dueAt);
        requireNonNull(command);
    }
}
