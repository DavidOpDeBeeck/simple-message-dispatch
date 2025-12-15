package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventBusInterceptor {

    <E extends Event> void intercept(EventMessage<E> eventMessage, EventBusInterceptorChain<E> chain);
}
