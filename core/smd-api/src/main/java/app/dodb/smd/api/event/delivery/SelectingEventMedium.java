package app.dodb.smd.api.event.delivery;

import static java.util.Objects.requireNonNull;

final class SelectingEventMedium implements EventMedium {

    private final EventSink inlet;
    private final EventSource outlet;

    SelectingEventMedium(EventMedium delegate, EventSelector selector) {
        requireNonNull(delegate);
        requireNonNull(selector);
        this.inlet = delegate.inlet().selecting(selector);
        this.outlet = delegate.outlet().selecting(selector);
    }

    @Override
    public EventSink inlet() {
        return inlet;
    }

    @Override
    public EventSource outlet() {
        return outlet;
    }
}
