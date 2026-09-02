package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventInterceptor;
import app.dodb.smd.api.event.EventInterceptorChain;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.channel.EventSink;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class EventBus implements EventPublisher {

    private final MetadataFactory metadataFactory;
    private final List<EventInterceptor> interceptors;
    private final List<EventSink> eventSinks;

    EventBus(MetadataFactory metadataFactory,
             List<EventInterceptor> interceptors,
             Collection<EventSink> eventSinks) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.interceptors = List.copyOf(interceptors);
        this.eventSinks = List.copyOf(eventSinks);
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
    public <E extends Event> void publish(E event, Metadata eventMetadata) {
        var chain = EventInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope(eventMetadata).run(
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
        for (var sink : eventSinks) {
            sink.send(eventMessage);
        }
    }
}
