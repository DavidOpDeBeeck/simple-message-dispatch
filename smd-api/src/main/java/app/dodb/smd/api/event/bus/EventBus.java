package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandlerDispatcher;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class EventBus implements EventPublisher {

    private final MetadataFactory metadataFactory;
    private final EventHandlerDispatcher dispatcher;
    private final List<EventBusInterceptor> interceptors;

    public EventBus(MetadataFactory metadataFactory,
                    EventHandlerDispatcher dispatcher,
                    List<EventBusInterceptor> interceptors) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.dispatcher = requireNonNull(dispatcher);
        this.interceptors = requireNonNull(interceptors);
    }

    @Override
    public <E extends Event> void publish(E event) {
        var chain = EventBusInterceptorChain.<E>create(dispatcher::dispatch, interceptors);
        metadataFactory.createScope().run(metadata -> {
            chain.proceed(EventMessage.from(event, metadata));
        });
    }

    @Override
    public <E extends Event> void publish(EventMessage<E> eventMessage) {
        var chain = EventBusInterceptorChain.<E>create(dispatcher::dispatch, interceptors);
        metadataFactory.createScope(eventMessage.getMetadata()).run(metadata -> {
            chain.proceed(eventMessage.withMetadata(metadata));
        });
    }
}
