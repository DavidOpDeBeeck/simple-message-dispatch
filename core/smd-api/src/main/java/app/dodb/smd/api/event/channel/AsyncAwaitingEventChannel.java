package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventInterceptorChain;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.MetadataFactory;

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
        return new AsyncAwaitingEventChannel(newVirtualThreadPerTaskExecutor(), List.of());
    }

    public static AsyncAwaitingEventChannel usingVirtualThreads(List<EventInterceptor> interceptors) {
        return new AsyncAwaitingEventChannel(newVirtualThreadPerTaskExecutor(), interceptors);
    }

    public static AsyncAwaitingEventChannel using(ExecutorService executorService) {
        return new AsyncAwaitingEventChannel(executorService, List.of());
    }

    public static AsyncAwaitingEventChannel using(ExecutorService executorService, List<EventInterceptor> interceptors) {
        return new AsyncAwaitingEventChannel(executorService, interceptors);
    }

    private final ExecutorService executorService;
    private final List<EventInterceptor> interceptors;
    private final List<EventChannelListener> listeners;

    private AsyncAwaitingEventChannel(ExecutorService executorService, List<EventInterceptor> interceptors) {
        this.executorService = requireNonNull(executorService);
        this.interceptors = requireNonNull(interceptors);
        this.listeners = new ArrayList<>();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        var futures = new ArrayList<Future<?>>();
        var failures = new ArrayList<Throwable>();

        try {
            for (EventChannelListener listener : listeners) {
                futures.add(executorService.submit(() -> {
                    MetadataFactory.runInScope(eventMessage, () -> {
                        var chain = EventInterceptorChain.<E>create(listener::on, interceptors);
                        chain.proceed(eventMessage);
                    });
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
            throw rethrow(e);
        } catch (Exception e) {
            throw rethrow(e);
        }

        if (failures.isEmpty()) {
            return;
        }

        var primaryFailure = failures.getFirst();
        failures.stream().skip(1).forEach(primaryFailure::addSuppressed);

        throw rethrow(primaryFailure);
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        listeners.add(listener);
    }
}
