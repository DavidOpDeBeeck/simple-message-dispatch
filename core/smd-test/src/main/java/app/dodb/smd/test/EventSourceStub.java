package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;

public class EventSourceStub implements EventSource {

    private static final EventDelivery TESTING = new EventDelivery("testing");

    private final List<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final List<EventMessage<?>> eventMessages = new CopyOnWriteArrayList<>();

    @Override
    public EventSubscription subscribe(EventSubscriber subscriber) {
        var registration = new EventSubscriberStub(subscriber.name(), subscriber::on);
        subscribers.add(registration);
        return () -> subscribers.remove(registration);
    }

    @Override
    public EventDelivery delivery() {
        return TESTING;
    }

    public <E extends Event> void send(EventMessage<E> eventMessage) {
        eventMessages.add(eventMessage);
        try {
            MetadataFactory.runInScope(eventMessage, () -> {
                for (var subscriber : subscribers) {
                    subscriber.on(eventMessage);
                }
            });
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    public List<EventMessage<?>> getEventMessages() {
        return List.copyOf(eventMessages);
    }

    public void reset() {
        eventMessages.clear();
    }
}
