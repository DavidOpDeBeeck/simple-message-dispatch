package app.dodb.smd.api.event;

import app.dodb.smd.api.metadata.MetadataFactory;

import static java.util.Objects.requireNonNull;

public class EventBus implements EventPublisher {

    private final MetadataFactory metadataFactory;
    private final EventHandlerDispatcher dispatcher;

    public EventBus(MetadataFactory metadataFactory,
                    EventHandlerDispatcher dispatcher) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.dispatcher = requireNonNull(dispatcher);
    }

    @Override
    public <E extends Event> void publish(E event) {
        publish(EventMessage.from(event, metadataFactory.create()));
    }

    @Override
    public <E extends Event> void publish(EventMessage<E> eventMessage) {
        dispatcher.dispatch(eventMessage);
    }
}
