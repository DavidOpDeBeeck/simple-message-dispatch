package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.event.EventInterceptorChain;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class EventBus implements EventPublisher {

    private final MetadataFactory metadataFactory;
    private final List<EventInterceptor> interceptors;
    private final Set<EventChannel> eventChannels;

    EventBus(MetadataFactory metadataFactory,
             List<EventInterceptor> interceptors,
             Set<EventChannel> eventChannels) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.interceptors = requireNonNull(interceptors);
        this.eventChannels = requireNonNull(eventChannels);
    }

    @Override
    public <E extends Event> void publish(E event) {
        var chain = EventInterceptorChain.<E>create(this::dispatch, interceptors);

        metadataFactory.createScope().run(
            metadata -> EventMessage.from(event, metadata),
            chain::proceed
        );
    }

    @Override
    public <E extends Event> void publish(EventMessage<E> eventMessage) {
        var chain = EventInterceptorChain.<E>create(this::dispatch, interceptors);

        metadataFactory.createScope(eventMessage.metadata()).run(
            eventMessage::withMetadata,
            chain::proceed
        );
    }

    private <E extends Event> void dispatch(EventMessage<E> eventMessage) {
        eventChannels.forEach(eventChannel -> eventChannel.send(eventMessage));
    }
}
