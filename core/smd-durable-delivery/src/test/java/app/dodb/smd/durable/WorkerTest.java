package app.dodb.smd.durable;

import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.event.delivery.RetryBackoffStrategy;
import app.dodb.smd.durable.DurableEventDispatcherConfig.SchedulingConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.exponential;
import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.fixed;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerTest {

    private static final RetryBackoffStrategy IDLE_BACKOFF = exponential(ofMillis(200), 2.0, ofMillis(500));

    private final StepScheduler scheduler = new StepScheduler();

    @Test
    void start_schedulesTheFirstStepImmediately() {
        try (var _ = start(() -> true, IDLE_BACKOFF)) {
            assertThat(scheduler.delaysInMillis()).containsExactly(0L);
        }
    }

    @Test
    void step_withWork_schedulesTheNextStepImmediately() {
        try (var _ = start(() -> true, IDLE_BACKOFF)) {
            scheduler.runSteps(3);

            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 0L, 0L, 0L);
        }
    }

    @Test
    void step_withoutWork_waitsTheIdleBackoff() {
        try (var _ = start(() -> false, IDLE_BACKOFF)) {
            scheduler.runSteps(4);

            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 200L, 400L, 500L, 500L);
        }
    }

    @Test
    void step_findingWorkAfterIdleSteps_restartsTheIdleBackoff() {
        var results = new ArrayDeque<>(List.of(false, false, true, false));
        try (var _ = start(results::remove, IDLE_BACKOFF)) {
            scheduler.runSteps(4);

            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 200L, 400L, 0L, 200L);
        }
    }

    @Test
    void step_withoutWorkAndFixedIdleBackoff_waitsTheSameDelay() {
        try (var _ = start(() -> false, fixed(ofMillis(300)))) {
            scheduler.runSteps(3);

            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 300L, 300L, 300L);
        }
    }

    @Test
    void step_fails_backsOffLikeAStepWithoutWork() {
        try (var _ = start(() -> {
            throw new IllegalStateException("Database unavailable");
        }, IDLE_BACKOFF)) {
            scheduler.runSteps(2);
            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 200L, 400L);
        }
    }

    @Test
    void step_throwsCheckedException_backsOffLikeAStepWithoutWork() {
        try (var _ = start(() -> {
            sneakyThrow(new IOException("Broker unavailable"));
            return true;
        }, IDLE_BACKOFF)) {
            scheduler.runSteps(2);

            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 200L, 400L);
        }
    }

    @Test
    void step_throwsError_backsOffLikeAStepWithoutWork() {
        try (var _ = start(() -> {
            throw new AssertionError("Handler bug");
        }, IDLE_BACKOFF)) {
            scheduler.runSteps(2);

            assertThat(scheduler.delaysInMillis()).containsExactly(0L, 200L, 400L);
        }
    }

    @Test
    void close_withScheduledStep_takesNoMoreSteps() {
        var steps = new AtomicInteger();
        var subscription = start(counting(steps), IDLE_BACKOFF);

        subscription.close();
        scheduler.runSteps(1);

        assertThat(steps).hasValue(0);
        assertThat(scheduler.delaysInMillis()).containsExactly(0L);
    }

    @Test
    void step_afterSchedulerShutdown_takesNoStepAndSchedulesNoOther() {
        var steps = new AtomicInteger();
        try (var _ = start(counting(steps), IDLE_BACKOFF)) {
            scheduler.shutdown();
            scheduler.runSteps(1);

            assertThat(steps).hasValue(0);
            assertThat(scheduler.delaysInMillis()).containsExactly(0L);
        }
    }

    private EventSubscription start(BooleanSupplier step, RetryBackoffStrategy idleBackoff) {
        return Worker.start(step, SchedulingConfig.withoutDefaults()
            .scheduler(scheduler)
            .idleBackoffStrategy(idleBackoff)
            .build());
    }

    private static BooleanSupplier counting(AtomicInteger steps) {
        return () -> {
            steps.incrementAndGet();
            return true;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private static final class StepScheduler extends ScheduledThreadPoolExecutor {

        private final List<ScheduledStep> scheduledSteps = new ArrayList<>();

        private StepScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            var step = new ScheduledStep(command, unit.toMillis(delay));
            scheduledSteps.add(step);
            return step;
        }

        private void runSteps(int count) {
            for (int run = 0; run < count; run++) {
                scheduledSteps.getLast().run();
            }
        }

        private List<Long> delaysInMillis() {
            return scheduledSteps.stream().map(ScheduledStep::delayInMillis).toList();
        }
    }

    private static final class ScheduledStep extends FutureTask<Void> implements ScheduledFuture<Void> {

        private final long delayInMillis;

        private ScheduledStep(Runnable command, long delayInMillis) {
            super(command, null);
            this.delayInMillis = delayInMillis;
        }

        private long delayInMillis() {
            return delayInMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayInMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
