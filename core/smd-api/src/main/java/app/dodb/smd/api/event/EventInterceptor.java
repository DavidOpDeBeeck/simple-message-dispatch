package app.dodb.smd.api.event;

public interface EventInterceptor {

    <E extends Event> void intercept(EventMessage<E> eventMessage, EventInterceptorChain<E> chain);
}
