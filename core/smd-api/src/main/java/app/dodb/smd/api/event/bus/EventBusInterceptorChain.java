package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public record EventBusInterceptorChain<E extends Event>(Consumer<EventMessage<E>> delegate, Deque<EventBusInterceptor> interceptors) {

    public static <E extends Event> EventBusInterceptorChain<E> create(Consumer<EventMessage<E>> delegate, List<EventBusInterceptor> interceptors) {
        return new EventBusInterceptorChain<>(delegate, new ArrayDeque<>(interceptors));
    }

    public EventBusInterceptorChain {
        requireNonNull(delegate);
        requireNonNull(interceptors);
    }

    public void proceed(EventMessage<E> eventMessage) {
        if (interceptors.isEmpty()) {
            delegate.accept(eventMessage);
            return;
        }
        interceptors.pop().intercept(eventMessage, this);
    }
}
