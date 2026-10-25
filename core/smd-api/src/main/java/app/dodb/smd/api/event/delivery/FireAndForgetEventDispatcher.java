package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
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
        return new FireAndForgetEventDispatcher(newVirtualThreadPerTaskExecutor());
    }

    public static FireAndForgetEventDispatcher using(ExecutorService executorService) {
        return new FireAndForgetEventDispatcher(executorService);
    }

    private final ExecutorService executorService;
    private final List<EventSubscriber> subscribers;
    private final EventSink inlet = new Inlet();
    private final EventSource outlet = new Outlet();

    private FireAndForgetEventDispatcher(ExecutorService executorService) {
        this.executorService = requireNonNull(executorService);
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
                    MetadataFactory.runInScope(eventMessage, () -> subscriber.on(eventMessage));
                } catch (Exception e) {
                    LOGGER.error("Unhandled event error: processingGroup={}", subscriber.name(), e);
                }
            }));
        }

        @Override
        public EventDelivery delivery() {
            return EventDelivery.ASYNC_FIRE_AND_FORGET;
        }
    }

    private class Outlet implements EventSource {

        @Override
        public EventSubscription subscribe(EventSubscriber subscriber) {
            var registration = new EventSubscriberRegistration(subscriber, subscribers);
            subscribers.add(registration);
            return registration;
        }

        @Override
        public EventDelivery delivery() {
            return EventDelivery.ASYNC_FIRE_AND_FORGET;
        }
    }
}
