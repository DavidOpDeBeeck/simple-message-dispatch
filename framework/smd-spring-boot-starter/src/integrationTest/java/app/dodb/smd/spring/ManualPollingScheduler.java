package app.dodb.smd.spring;

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
public final class ManualPollingScheduler extends ScheduledThreadPoolExecutor {

    private final List<ManualTask> pollingTasks = new CopyOnWriteArrayList<>();
    private final List<ManualTask> scheduledTasks = new CopyOnWriteArrayList<>();

    public ManualPollingScheduler() {
        super(1);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        var task = new ManualTask(command);
        pollingTasks.add(task);
        return task;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        var task = new ManualTask(command);
        scheduledTasks.add(task);
        return task;
    }

    public void poll() throws Exception {
        pollAsync().get(5, SECONDS);
    }

    // A poll runs the tasks scheduled before it; tasks they schedule wait for the next poll.
    // submit() would go through the overridden schedule(), so the poll itself uses super.schedule().
    public Future<?> pollAsync() {
        return super.schedule(() -> {
            for (var task : pollingTasks) {
                task.run();
            }
            var dueTasks = List.copyOf(scheduledTasks);
            scheduledTasks.removeAll(dueTasks);
            for (var task : dueTasks) {
                task.run();
            }
        }, 0, SECONDS);
    }

    private static final class ManualTask extends FutureTask<Void> implements ScheduledFuture<Void> {

        private ManualTask(Runnable command) {
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
