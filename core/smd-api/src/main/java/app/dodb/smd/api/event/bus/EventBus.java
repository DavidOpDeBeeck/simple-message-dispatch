package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;

public class EventBus implements EventPublisher {

    private final MetadataFactory metadataFactory;
    private final List<EventBusInterceptor> interceptors;
    private final List<EventSinkInterceptor> sinkInterceptors;
    private final List<EventSink> eventSinks;

    EventBus(MetadataFactory metadataFactory,
             List<EventBusInterceptor> interceptors,
             List<EventSinkInterceptor> sinkInterceptors,
             Collection<EventSink> eventSinks) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.interceptors = requireNonNull(interceptors);
        this.sinkInterceptors = requireNonNull(sinkInterceptors);
        this.eventSinks = List.copyOf(eventSinks);
    }

    @Override
    public <E extends Event> void publish(E event) {
        var chain = EventBusInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope().run(
            metadata -> EventMessage.from(event, metadata),
            chain::proceed
        );
    }

    @Override
    public <E extends Event> void publish(E event, Metadata eventMetadata) {
        var chain = EventBusInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope(eventMetadata).run(
            metadata -> EventMessage.from(event, metadata),
            chain::proceed
        );
    }

    @Override
    public <E extends Event> void publish(E event, UnaryOperator<Metadata> metadataUpdate) {
        var chain = EventBusInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope(metadataUpdate).run(
            metadata -> EventMessage.from(event, metadata),
            chain::proceed
        );
    }

    @Override
    public <E extends Event> void publish(EventMessage<E> eventMessage) {
        var chain = EventBusInterceptorChain.<E>create(this::dispatch, interceptors);
        metadataFactory.createScope(eventMessage.metadata()).run(
            eventMessage::withMetadata,
            chain::proceed
        );
    }

    private <E extends Event> void dispatch(EventMessage<E> eventMessage) {
        for (var sink : eventSinks) {
            EventSinkInterceptorChain.<E>create(context -> context.sink().send(context.eventMessage()), sinkInterceptors)
                .proceed(new EventSinkContext<>(sink, eventMessage));
        }
    }
}
