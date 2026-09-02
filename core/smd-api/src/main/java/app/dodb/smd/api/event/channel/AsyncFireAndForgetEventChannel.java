package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventInterceptorChain;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.MetadataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class AsyncFireAndForgetEventChannel implements EventChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncFireAndForgetEventChannel.class);

    public static AsyncFireAndForgetEventChannel usingVirtualThreads() {
        return new AsyncFireAndForgetEventChannel(newVirtualThreadPerTaskExecutor(), List.of());
    }

    public static AsyncFireAndForgetEventChannel usingVirtualThreads(List<EventInterceptor> interceptors) {
        return new AsyncFireAndForgetEventChannel(newVirtualThreadPerTaskExecutor(), interceptors);
    }

    public static AsyncFireAndForgetEventChannel using(ExecutorService executorService) {
        return new AsyncFireAndForgetEventChannel(executorService, List.of());
    }

    public static AsyncFireAndForgetEventChannel using(ExecutorService executorService, List<EventInterceptor> interceptors) {
        return new AsyncFireAndForgetEventChannel(executorService, interceptors);
    }

    private final ExecutorService executorService;
    private final List<EventInterceptor> interceptors;
    private final List<EventChannelListener> listeners;

    private AsyncFireAndForgetEventChannel(ExecutorService executorService, List<EventInterceptor> interceptors) {
        this.executorService = requireNonNull(executorService);
        this.interceptors = requireNonNull(interceptors);
        this.listeners = new ArrayList<>();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        listeners.forEach(listener -> executorService.submit(() -> {
            try {
                MetadataFactory.runInScope(eventMessage, () -> {
                    var chain = EventInterceptorChain.<E>create(listener::on, interceptors);
                    chain.proceed(eventMessage);
                });
            } catch (Exception e) {
                LOGGER.error("Unhandled event error: processingGroup={}", listener.processingGroup(), e);
            }
        }));
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        listeners.add(listener);
    }
}
