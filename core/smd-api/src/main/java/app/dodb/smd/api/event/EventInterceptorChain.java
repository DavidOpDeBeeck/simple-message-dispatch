package app.dodb.smd.api.event;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public record EventInterceptorChain<E extends Event>(Consumer<EventMessage<E>> delegate, Deque<EventInterceptor> interceptors) {

    public static <E extends Event> EventInterceptorChain<E> create(Consumer<EventMessage<E>> delegate, List<? extends EventInterceptor> interceptors) {
        return new EventInterceptorChain<>(delegate, new ArrayDeque<>(interceptors));
    }

    public EventInterceptorChain {
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
