package app.dodb.smd.spring.eventstore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

// Keep real worker threads and subscription cancellation, but let each test choose when polling happens.
final class ManualPollingScheduler extends ScheduledThreadPoolExecutor {

    private final List<PollingTask> pollingTasks = new CopyOnWriteArrayList<>();

    ManualPollingScheduler() {
        super(1);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        var task = new PollingTask(command);
        pollingTasks.add(task);
        return task;
    }

    void poll() throws Exception {
        pollAsync().get(5, SECONDS);
    }

    Future<?> pollAsync() {
        return submit(() -> {
            for (var task : pollingTasks) {
                task.run();
            }
        });
    }

    private static final class PollingTask extends FutureTask<Void> implements ScheduledFuture<Void> {

        private PollingTask(Runnable command) {
            super(command, null);
        }

        @Override
        public void run() {
            if (!runAndReset() && !isCancelled()) {
                try {
                    get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Polling worker interrupted", e);
                } catch (ExecutionException e) {
                    throw new AssertionError("Polling worker failed", e.getCause());
                }
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
