package app.dodb.smd.api.event.delivery;

import app.dodb.smd.api.event.Event;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public record EventSinkInterceptorChain<E extends Event>(Consumer<EventSinkContext<E>> delegate, Deque<EventSinkInterceptor> interceptors) {

    public static <E extends Event> EventSinkInterceptorChain<E> create(Consumer<EventSinkContext<E>> delegate, List<? extends EventSinkInterceptor> interceptors) {
        return new EventSinkInterceptorChain<>(delegate, new ArrayDeque<>(interceptors));
    }

    public EventSinkInterceptorChain {
        requireNonNull(delegate);
        requireNonNull(interceptors);
    }

    public void proceed(EventSinkContext<E> context) {
        if (interceptors.isEmpty()) {
            delegate.accept(context);
            return;
        }
        interceptors.pop().intercept(context, this);
    }
}
