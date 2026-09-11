package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventInterceptorChain;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.MetadataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class FireAndForgetEventDispatcher implements EventMedium {

    private static final Logger LOGGER = LoggerFactory.getLogger(FireAndForgetEventDispatcher.class);

    public static FireAndForgetEventDispatcher usingVirtualThreads() {
        return new FireAndForgetEventDispatcher(newVirtualThreadPerTaskExecutor(), List.of());
    }

    public static FireAndForgetEventDispatcher usingVirtualThreads(List<EventInterceptor> interceptors) {
        return new FireAndForgetEventDispatcher(newVirtualThreadPerTaskExecutor(), interceptors);
    }

    public static FireAndForgetEventDispatcher using(ExecutorService executorService) {
        return new FireAndForgetEventDispatcher(executorService, List.of());
    }

    public static FireAndForgetEventDispatcher using(ExecutorService executorService, List<EventInterceptor> interceptors) {
        return new FireAndForgetEventDispatcher(executorService, interceptors);
    }

    private final ExecutorService executorService;
    private final List<EventInterceptor> interceptors;
    private final List<EventSubscriber> subscribers;
    private final EventSink inlet = new Inlet();
    private final EventSource outlet = new Outlet();

    private FireAndForgetEventDispatcher(ExecutorService executorService, List<EventInterceptor> interceptors) {
        this.executorService = requireNonNull(executorService);
        this.interceptors = requireNonNull(interceptors);
        this.subscribers = new CopyOnWriteArrayList<>();
    }

    @Override
    public EventSink inlet() {
        return inlet;
    }

    @Override
    public EventSource outlet() {
        return outlet;
    }

    private class Inlet implements EventSink {

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            subscribers.forEach(subscriber -> executorService.submit(() -> {
                try {
                    MetadataFactory.runInScope(eventMessage, () -> {
                        var chain = EventInterceptorChain.<E>create(subscriber::on, interceptors);
                        chain.proceed(eventMessage);
                    });
                } catch (Exception e) {
                    LOGGER.error("Unhandled event error: processingGroup={}", subscriber.processingGroup(), e);
                }
            }));
        }
    }

    private class Outlet implements EventSource {

        @Override
        public EventSubscription subscribe(EventSubscriber subscriber) {
            var registration = new EventSubscriberRegistration(subscriber, subscribers);
            subscribers.add(registration);
            return registration;
        }
    }
}
