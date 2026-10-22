package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.event.delivery.EventSubscriber;

import java.util.List;

import static java.util.Objects.requireNonNull;

class EventStoreOutboxForwarder implements EventSubscriber {

    private final String outboxName;
    private final EventSink sink;
    private final List<EventSinkInterceptor> sinkInterceptors;

    EventStoreOutboxForwarder(String outboxName, EventSink sink, List<EventSinkInterceptor> sinkInterceptors) {
        this.outboxName = requireNonNull(outboxName);
        this.sink = requireNonNull(sink);
        this.sinkInterceptors = requireNonNull(sinkInterceptors);
    }

    @Override
    public String processingGroup() {
        return outboxName;
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        EventSinkInterceptorChain.<E>create(context -> context.sink().send(context.eventMessage()), sinkInterceptors)
            .proceed(new EventSinkContext<>(sink, eventMessage));
    }
}
