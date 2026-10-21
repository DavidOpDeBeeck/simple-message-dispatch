package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.api.event.EventPublisher;

import java.time.Duration;

public class FundsReservationManager {

    public static final Duration RESERVATION_TIMEOUT = Duration.ofMinutes(15);

    private final DeadlineScheduler deadlineScheduler;
    private final EventPublisher eventPublisher;

    public FundsReservationManager(DeadlineScheduler deadlineScheduler, EventPublisher eventPublisher) {
        this.deadlineScheduler = deadlineScheduler;
        this.eventPublisher = eventPublisher;
    }

    @CommandHandler
    public void handle(ReserveFundsCommand command) {
        deadlineScheduler.schedule(new ExpireReservationCommand(command.account()), RESERVATION_TIMEOUT);
    }

    @CommandHandler
    public void handle(ExpireReservationCommand command) {
        eventPublisher.publish(new ReservationExpiredEvent(command.account()));
    }
}
