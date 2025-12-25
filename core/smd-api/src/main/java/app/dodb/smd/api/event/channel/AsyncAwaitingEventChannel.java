package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class AsyncAwaitingEventChannel implements EventChannel {

    public static AsyncAwaitingEventChannel usingVirtualThreads() {
        return new AsyncAwaitingEventChannel(newVirtualThreadPerTaskExecutor());
    }

    public static AsyncAwaitingEventChannel using(ExecutorService executorService) {
        return new AsyncAwaitingEventChannel(executorService);
    }

    private final ExecutorService executorService;
    private final List<EventChannelListener> listeners = new ArrayList<>();

    private AsyncAwaitingEventChannel(ExecutorService executorService) {
        this.executorService = requireNonNull(executorService);
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        try {
            var futures = new ArrayList<Future<?>>();

            for (EventChannelListener listener : listeners) {
                futures.add(executorService.submit(() -> listener.on(eventMessage)));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw rethrow(e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        listeners.add(listener);
    }
}
