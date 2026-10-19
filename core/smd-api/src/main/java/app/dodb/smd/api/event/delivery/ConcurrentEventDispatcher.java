package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class ConcurrentEventDispatcher implements EventMedium {

    public static ConcurrentEventDispatcher usingVirtualThreads() {
        return new ConcurrentEventDispatcher(newVirtualThreadPerTaskExecutor());
    }

    public static ConcurrentEventDispatcher using(ExecutorService executorService) {
        return new ConcurrentEventDispatcher(executorService);
    }

    private final ExecutorService executorService;
    private final List<EventSubscriber> subscribers;
    private final EventSink inlet = new Inlet();
    private final EventSource outlet = new Outlet();

    private ConcurrentEventDispatcher(ExecutorService executorService) {
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
            var futures = new ArrayList<Future<?>>();
            var failures = new ArrayList<Throwable>();

            try {
                for (EventSubscriber subscriber : subscribers) {
                    futures.add(executorService.submit(() -> {
                        MetadataFactory.runInScope(eventMessage, () -> subscriber.on(eventMessage));
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
        public EventDelivery delivery() {
            return EventDelivery.ASYNC_AWAIT;
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
            return EventDelivery.ASYNC_AWAIT;
        }
    }
}
