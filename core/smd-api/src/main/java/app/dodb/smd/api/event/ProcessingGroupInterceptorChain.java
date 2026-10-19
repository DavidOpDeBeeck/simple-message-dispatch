package app.dodb.smd.api.event;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public record ProcessingGroupInterceptorChain<E extends Event>(Consumer<ProcessingGroupContext<E>> delegate, Deque<ProcessingGroupInterceptor> interceptors) {

    public static <E extends Event> ProcessingGroupInterceptorChain<E> create(Consumer<ProcessingGroupContext<E>> delegate, List<? extends ProcessingGroupInterceptor> interceptors) {
        return new ProcessingGroupInterceptorChain<>(delegate, new ArrayDeque<>(interceptors));
    }

    public ProcessingGroupInterceptorChain {
        requireNonNull(delegate);
        requireNonNull(interceptors);
    }

    public void proceed(ProcessingGroupContext<E> context) {
        if (interceptors.isEmpty()) {
            delegate.accept(context);
            return;
        }
        interceptors.pop().intercept(context, this);
    }
}
