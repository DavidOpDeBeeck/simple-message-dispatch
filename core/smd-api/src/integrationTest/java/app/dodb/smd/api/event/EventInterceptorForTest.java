package app.dodb.smd.api.event;

import java.util.ArrayList;
import java.util.List;

public class EventInterceptorForTest implements EventInterceptor {

    private final List<Event> interceptedEvents = new ArrayList<>();

    @Override
    public <E extends Event> void intercept(EventMessage<E> eventMessage, EventInterceptorChain<E> chain) {
        interceptedEvents.add(eventMessage.payload());
        chain.proceed(eventMessage);
    }

    public List<Event> getInterceptedEvents() {
        return interceptedEvents;
    }
}
