package app.dodb.smd.api.deadline;

import app.dodb.smd.api.command.Command;

import java.time.Duration;
import java.time.Instant;

public interface DeadlineScheduler {

    DeadlineId schedule(Command<?> command, Instant dueAt);

    DeadlineId schedule(Command<?> command, Duration delay);

    boolean cancel(DeadlineId deadlineId);
}
