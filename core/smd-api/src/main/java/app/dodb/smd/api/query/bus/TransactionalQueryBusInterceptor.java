package app.dodb.smd.api.query.bus;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryMessage;

public class TransactionalQueryBusInterceptor implements QueryBusInterceptor {

    private final TransactionProvider transactionProvider;

    public TransactionalQueryBusInterceptor(TransactionProvider transactionProvider) {
        this.transactionProvider = transactionProvider;
    }

    @Override
    public <R, Q extends Query<R>> R intercept(QueryMessage<R, Q> queryMessage, QueryBusInterceptorChain<R, Q> chain) {
        return transactionProvider.doInReadOnlyTransaction(() -> chain.proceed(queryMessage));
    }
}
