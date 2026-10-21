package app.dodb.smd.spring.eventstore;

import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

// Keep real worker threads and subscription cancellation, but let each test choose when polling happens.
// Each poll() runs the polls scheduled so far once; polls they schedule in turn wait for the next poll().
final class ManualPollingScheduler extends ScheduledThreadPoolExecutor {

    private final Queue<PollingTask> scheduledPolls = new ConcurrentLinkedQueue<>();

    ManualPollingScheduler() {
        super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        var task = new PollingTask(command);
        scheduledPolls.add(task);
        return task;
    }

    void poll() throws Exception {
        pollAsync().get(5, SECONDS);
    }

    Future<?> pollAsync() {
        // Submit a Callable: submitting a Runnable would route through the overridden schedule(Runnable, ...).
        return submit(() -> {
            var polls = new ArrayList<PollingTask>();
            for (var task = scheduledPolls.poll(); task != null; task = scheduledPolls.poll()) {
                polls.add(task);
            }
            for (var task : polls) {
                task.run();
            }
            return null;
        });
    }

    private static final class PollingTask extends FutureTask<Void> implements ScheduledFuture<Void> {

        private PollingTask(Runnable command) {
            super(command, null);
        }

        @Override
        public void run() {
            super.run();
            if (isCancelled()) {
                return;
            }
            try {
                get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Polling worker interrupted", e);
            } catch (ExecutionException e) {
                throw new AssertionError("Polling worker failed", e.getCause());
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
