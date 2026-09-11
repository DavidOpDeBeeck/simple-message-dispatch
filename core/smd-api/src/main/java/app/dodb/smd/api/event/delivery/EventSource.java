package app.dodb.smd.api.event.delivery;

public interface EventSource {

    EventSubscription subscribe(EventSubscriber subscriber);

    default EventSource selecting(EventSelector selector) {
        return new SelectingEventSource(this, selector);
    }
}
