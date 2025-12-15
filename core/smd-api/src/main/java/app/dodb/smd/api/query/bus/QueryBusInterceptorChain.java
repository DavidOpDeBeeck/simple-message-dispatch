package app.dodb.smd.api.query.bus;

import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public record QueryBusInterceptorChain<R, Q extends Query<R>>(Function<QueryMessage<R, Q>, R> delegate, Deque<QueryBusInterceptor> interceptors) {

    public static <R, Q extends Query<R>> QueryBusInterceptorChain<R, Q> create(Function<QueryMessage<R, Q>, R> delegate, List<QueryBusInterceptor> interceptors) {
        return new QueryBusInterceptorChain<>(delegate, new ArrayDeque<>(interceptors));
    }

    public QueryBusInterceptorChain {
        requireNonNull(delegate);
        requireNonNull(interceptors);
    }

    public R proceed(QueryMessage<R, Q> queryMessage) {
        if (interceptors.isEmpty()) {
            return delegate.apply(queryMessage);
        }
        return interceptors.pop().intercept(queryMessage, this);
    }
}
