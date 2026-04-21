package app.dodb.smd.api.event;

import app.dodb.smd.api.framework.TransactionProvider;

public class TransactionalEventInterceptor implements EventInterceptor {

    private final TransactionProvider transactionProvider;

    public TransactionalEventInterceptor(TransactionProvider transactionProvider) {
        this.transactionProvider = transactionProvider;
    }

    @Override
    public <E extends Event> void intercept(EventMessage<E> eventMessage, EventInterceptorChain<E> chain) {
        transactionProvider.doInTransaction(() -> chain.proceed(eventMessage));
    }
}
