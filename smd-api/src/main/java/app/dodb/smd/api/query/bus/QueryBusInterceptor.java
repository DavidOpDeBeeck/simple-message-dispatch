package app.dodb.smd.api.query.bus;

import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryMessage;

public interface QueryBusInterceptor {

    <R, Q extends Query<R>> R intercept(QueryMessage<R, Q> queryMessage, QueryBusInterceptorChain<R, Q> chain);
}
