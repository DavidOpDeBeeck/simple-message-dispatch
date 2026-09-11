package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;

public class SynchronousEventDispatcher implements EventMedium {

    private final List<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final EventSink inlet = new Inlet();
    private final EventSource outlet = new Outlet();

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
            try {
                MetadataFactory.runInScope(eventMessage, () -> {
                    for (EventSubscriber subscriber : subscribers) {
                        subscriber.on(eventMessage);
                    }
                });
            } catch (Exception e) {
                throw rethrow(e);
            }
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
