package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import static java.util.Objects.requireNonNull;

public class SelectingEventSource implements EventSource {

    private final EventSource delegate;
    private final EventSelector selector;

    public SelectingEventSource(EventSource delegate, EventSelector selector) {
        this.delegate = requireNonNull(delegate);
        this.selector = requireNonNull(selector);
    }

    @Override
    public void subscribe(EventSubscriber subscriber) {
        delegate.subscribe(new SelectingEventSubscriber(subscriber));
    }

    private class SelectingEventSubscriber implements EventSubscriber {

        private final EventSubscriber delegate;

        private SelectingEventSubscriber(EventSubscriber delegate) {
            this.delegate = requireNonNull(delegate);
        }

        @Override
        public String processingGroup() {
            return delegate.processingGroup();
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            if (selector.selects(eventMessage)) {
                delegate.on(eventMessage);
            }
        }
    }
}
