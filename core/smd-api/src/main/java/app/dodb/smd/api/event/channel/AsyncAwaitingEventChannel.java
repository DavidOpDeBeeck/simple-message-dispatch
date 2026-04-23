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
        try {
            var futures = new ArrayList<Future<?>>();

            for (EventChannelListener listener : listeners) {
                futures.add(executorService.submit(() -> {
                    MetadataFactory.runInScope(eventMessage, () -> {
                        var chain = EventInterceptorChain.<E>create(listener::on, interceptors);
                        chain.proceed(eventMessage);
                    });
                }));
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
