package app.dodb.smd.test;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.api.metadata.time.TimeProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class DeadlineSchedulerStub implements DeadlineScheduler {

    private final TimeProvider timeProvider;
    private final List<ScheduledDeadline> deadlines = new ArrayList<>();

    public DeadlineSchedulerStub(TimeProvider timeProvider) {
        this.timeProvider = requireNonNull(timeProvider);
    }

    @Override
    public synchronized DeadlineId schedule(Command<?> command, Instant dueAt) {
        var deadline = new ScheduledDeadline(DeadlineId.generate(), dueAt, command);
        deadlines.add(deadline);
        return deadline.deadlineId();
    }

    @Override
    public DeadlineId schedule(Command<?> command, Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Deadline delay must not be negative: command=" + command.getClass().getName() + ", delay=" + delay);
        }
        return schedule(command, timeProvider.now().plus(delay));
    }

    @Override
    public synchronized boolean cancel(DeadlineId deadlineId) {
        return deadlines.removeIf(deadline -> deadline.deadlineId().equals(deadlineId));
    }

    public synchronized List<ScheduledDeadline> getDeadlines() {
        return deadlines.stream()
            .sorted(Comparator.comparing(ScheduledDeadline::dueAt))
            .toList();
    }

    public List<ScheduledDeadline> fireDue(CommandGateway commandGateway) {
        var now = timeProvider.now();
        var dueDeadlines = getDeadlines().stream()
            .filter(deadline -> !deadline.dueAt().isAfter(now))
            .toList();

        var firedDeadlines = new ArrayList<ScheduledDeadline>();
        for (var deadline : dueDeadlines) {
            // A command fired earlier in this round may have cancelled a later deadline.
            if (cancel(deadline.deadlineId())) {
                firedDeadlines.add(deadline);
                commandGateway.send(deadline.command());
            }
        }
        return List.copyOf(firedDeadlines);
    }

    public synchronized void reset() {
        deadlines.clear();
    }
}
