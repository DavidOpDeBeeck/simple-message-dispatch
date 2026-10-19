package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;

public interface EventSinkInterceptor {

    <E extends Event> void intercept(EventSinkContext<E> context, EventSinkInterceptorChain<E> chain);
}
