package app.dodb.smd.api.event;

import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.EventBusInterceptorChain;

import java.util.ArrayList;
import java.util.List;

public class EventBusInterceptorForTest implements EventBusInterceptor {

    private final List<Event> interceptedEvents = new ArrayList<>();

    @Override
    public <E extends Event> void intercept(EventMessage<E> eventMessage, EventBusInterceptorChain<E> chain) {
        interceptedEvents.add(eventMessage.payload());
        chain.proceed(eventMessage);
    }

    public List<Event> getInterceptedEvents() {
        return interceptedEvents;
    }
}
