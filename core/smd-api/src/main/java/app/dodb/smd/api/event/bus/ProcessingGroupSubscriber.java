package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandlerBehaviour;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroupContext;
import app.dodb.smd.api.event.ProcessingGroupInterceptor;
import app.dodb.smd.api.event.ProcessingGroupInterceptorChain;
import app.dodb.smd.api.event.ProcessingGroupRegistry.EventHandlerRegistry;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;

import static java.util.Objects.requireNonNull;

class ProcessingGroupSubscriber implements EventSubscriber {

    private final EventHandlerRegistry registry;
    private final EventSource source;
    private final List<ProcessingGroupInterceptor> interceptors;

    ProcessingGroupSubscriber(EventHandlerRegistry registry, EventSource source, List<ProcessingGroupInterceptor> interceptors) {
        this.registry = requireNonNull(registry);
        this.source = requireNonNull(source);
        this.interceptors = requireNonNull(interceptors);
    }

    @Override
    public String processingGroup() {
        return registry.processingGroup();
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        var handlers = registry.handlersFor(eventMessage);
        if (handlers.isEmpty()) {
            return;
        }

        ProcessingGroupInterceptorChain.<E>create(context -> dispatch(context, handlers), interceptors)
            .proceed(new ProcessingGroupContext<>(registry.processingGroup(), source, eventMessage));
    }

    private <E extends Event> void dispatch(ProcessingGroupContext<E> context, List<EventHandlerBehaviour<E>> handlers) {
        var eventMessage = context.eventMessage();
        MetadataFactory.runInScope(eventMessage, () -> handlers.forEach(handler -> handler.handle(eventMessage)));
    }
}
