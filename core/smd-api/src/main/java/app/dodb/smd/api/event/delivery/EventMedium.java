package app.dodb.smd.api.event.delivery;

public interface EventMedium {

    EventSink inlet();

    EventSource outlet();

    default EventMedium selecting(EventSelector selector) {
        return new SelectingEventMedium(this, selector);
    }
}
