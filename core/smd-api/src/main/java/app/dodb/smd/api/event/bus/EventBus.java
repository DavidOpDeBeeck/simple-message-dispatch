package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.channel.EventChannel;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class EventBus implements EventPublisher {

    private final MetadataFactory metadataFactory;
    private final List<EventBusInterceptor> interceptors;
    private final Set<EventChannel> eventChannels;

    EventBus(MetadataFactory metadataFactory,
             List<EventBusInterceptor> interceptors,
             Set<EventChannel> eventChannels) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.interceptors = requireNonNull(interceptors);
        this.eventChannels = requireNonNull(eventChannels);
    }

    @Override
    public <E extends Event> void publish(E event) {
        var chain = EventBusInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope().run(metadata -> {
            chain.proceed(EventMessage.from(event, metadata));
        });
    }

    @Override
    public <E extends Event> void publish(EventMessage<E> eventMessage) {
        var chain = EventBusInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope(eventMessage.getMetadata()).run(metadata -> {
            chain.proceed(eventMessage.withMetadata(metadata));
        });
    }

    private <E extends Event> void dispatch(EventMessage<E> eventMessage) {
        eventChannels.forEach(eventChannel -> eventChannel.send(eventMessage));
    }
}
