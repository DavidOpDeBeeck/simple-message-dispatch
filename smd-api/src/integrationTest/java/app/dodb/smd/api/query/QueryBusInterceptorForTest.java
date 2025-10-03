package app.dodb.smd.api.query;

import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.api.query.bus.QueryBusInterceptorChain;

import java.util.ArrayList;
import java.util.List;

public class QueryBusInterceptorForTest implements QueryBusInterceptor {

    private final List<Query<?>> interceptedQueries = new ArrayList<>();

    @Override
    public <R, Q extends Query<R>> R intercept(QueryMessage<R, Q> queryMessage, QueryBusInterceptorChain<R, Q> chain) {
        interceptedQueries.add(queryMessage.payload());
        return chain.proceed(queryMessage);
    }

    public List<Query<?>> getInterceptedQueries() {
        return interceptedQueries;
    }
}
