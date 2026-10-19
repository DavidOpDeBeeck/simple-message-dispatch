package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroupRegistry.EventHandlerRegistry;

import static java.util.Objects.requireNonNull;

record EventHandlersSubscriber(EventHandlerRegistry registry) implements EventSubscriber {

    static EventHandlersSubscriber from(Object eventHandler, String processingGroup) {
        return new EventHandlersSubscriber(AnnotatedEventHandler.from(eventHandler).findBy(processingGroup));
    }

    EventHandlersSubscriber {
        requireNonNull(registry);
    }

    @Override
    public String processingGroup() {
        return registry.processingGroup();
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        registry.handlersFor(eventMessage).forEach(handler -> handler.handle(eventMessage));
    }
}
