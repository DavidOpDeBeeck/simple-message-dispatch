package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.List;

import static java.util.Objects.requireNonNull;

class EventSubscriberRegistration implements EventSubscriber, EventSubscription {

    private final EventSubscriber subscriber;
    private final List<EventSubscriber> registrations;

    EventSubscriberRegistration(EventSubscriber subscriber, List<EventSubscriber> registrations) {
        this.subscriber = requireNonNull(subscriber);
        this.registrations = requireNonNull(registrations);
    }

    @Override
    public String processingGroup() {
        return subscriber.processingGroup();
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        subscriber.on(eventMessage);
    }

    @Override
    public void close() {
        registrations.remove(this);
    }
}
