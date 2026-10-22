package app.dodb.smd.spring.deadline;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ReminderHandler {

    private final DeadlineScheduler deadlineScheduler;
    private final JdbcTemplate jdbc;
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private volatile CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(0);

    public ReminderHandler(DeadlineScheduler deadlineScheduler, JdbcTemplate jdbc) {
        this.deadlineScheduler = deadlineScheduler;
        this.jdbc = jdbc;
    }

    @CommandHandler
    public DeadlineId handle(ScheduleReminderCommand command) {
        return deadlineScheduler.schedule(new RemindCustomerCommand(command.ticketId()), command.delay());
    }

    @CommandHandler
    public void handle(RemindCustomerCommand command, MessageId messageId, Metadata metadata) {
        attempts.add(new Attempt(messageId, command.ticketId(), metadata));
        jdbc.update("INSERT INTO ticket_reminder (message_id, ticket_id) VALUES (?, ?)", messageId.value(), command.ticketId());
        entered.countDown();
        awaitRelease();
        if (failuresRemaining.getAndDecrement() > 0) {
            throw new IllegalStateException("Simulated reminder failure");
        }
    }

    private void awaitRelease() {
        try {
            if (!release.await(10, SECONDS)) {
                throw new IllegalStateException("Blocked reminder was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while blocking a reminder", e);
        }
    }

    void reset() {
        attempts.clear();
        failuresRemaining.set(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(0);
    }

    void failNextAttempts(int failures) {
        failuresRemaining.set(failures);
    }

    void blockNextAttempt() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    void awaitBlockedAttempt() throws InterruptedException {
        if (!entered.await(10, SECONDS)) {
            throw new AssertionError("Reminder was not fired");
        }
    }

    void releaseBlockedAttempt() {
        release.countDown();
    }

    List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    public record ScheduleReminderCommand(String ticketId, Duration delay) implements Command<DeadlineId> {
    }

    public record RemindCustomerCommand(String ticketId) implements Command<Void> {
    }

    record Attempt(MessageId messageId, String ticketId, Metadata metadata) {
    }
}
