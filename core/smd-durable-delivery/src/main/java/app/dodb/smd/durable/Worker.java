package app.dodb.smd.durable;

import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static java.time.Duration.ZERO;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class Worker {

    private static final Logger LOGGER = LoggerFactory.getLogger(Worker.class);

    private final BooleanSupplier step;
    private final RetryBackoffStrategy idleBackoff;
    private final ScheduledExecutorService scheduler;
    private volatile boolean stopped;
    private volatile Future<?> nextStep;
    private int idleSteps;

    private Worker(BooleanSupplier step, RetryBackoffStrategy idleBackoff, ScheduledExecutorService scheduler) {
        this.step = requireNonNull(step);
        this.idleBackoff = requireNonNull(idleBackoff);
        this.scheduler = requireNonNull(scheduler);
    }

    static EventSubscription start(BooleanSupplier step, SchedulingConfig config) {
        var worker = new Worker(step, config.getIdleBackoffStrategy(), config.getScheduler());
        worker.scheduleNextStep(ZERO);
        return worker::stop;
    }

    private void run() {
        if (stopped || scheduler.isShutdown()) {
            return;
        }

        if (runStep()) {
            idleSteps = 0;
            scheduleNextStep(ZERO);
        } else {
            scheduleNextStep(idleBackoff.calculateDelay(idleSteps++));
        }
    }

    private boolean runStep() {
        try {
            return step.getAsBoolean();
        } catch (Throwable e) {
            LOGGER.error("Worker step failed", e);
            return false;
        }
    }

    private void scheduleNextStep(Duration delay) {
        try {
            nextStep = scheduler.schedule(this::run, delay.toMillis(), MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Worker stopped with its scheduler", e);
        }
    }

    private void stop() {
        stopped = true;
        var scheduledStep = nextStep;
        if (scheduledStep != null) {
            scheduledStep.cancel(false);
        }
    }
}
