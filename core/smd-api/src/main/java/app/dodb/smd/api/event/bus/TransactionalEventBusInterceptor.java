package app.dodb.smd.api.event.bus;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.framework.TransactionProvider;

public class TransactionalEventBusInterceptor implements EventBusInterceptor {

    private final TransactionProvider transactionProvider;

    public TransactionalEventBusInterceptor(TransactionProvider transactionProvider) {
        this.transactionProvider = transactionProvider;
    }

    @Override
    public <E extends Event> void intercept(EventMessage<E> eventMessage, EventBusInterceptorChain<E> chain) {
        transactionProvider.doInTransaction(() -> chain.proceed(eventMessage));
    }
}
