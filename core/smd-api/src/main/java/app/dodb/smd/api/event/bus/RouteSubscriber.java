package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventRoute;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.event.delivery.EventSubscriber;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

class RouteSubscriber implements EventSubscriber {

    private final EventRoute route;
    private final List<EventSinkInterceptor> interceptors;

    RouteSubscriber(EventRoute route, List<EventSinkInterceptor> interceptors) {
        this.route = requireNonNull(route);
        this.interceptors = requireNonNull(interceptors);
    }

    @Override
    public String name() {
        return route.name();
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        EventSinkInterceptorChain.<E>create(context -> context.sink().send(context.eventMessage()), interceptors)
            .proceed(new EventSinkContext<>(route.sink(), eventMessage, Optional.of(route)));
    }
}
