package app.dodb.smd.api.command.bus;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.framework.TransactionProvider;

public class TransactionalCommandBusInterceptor implements CommandBusInterceptor {

    private final TransactionProvider transactionProvider;

    public TransactionalCommandBusInterceptor(TransactionProvider transactionProvider) {
        this.transactionProvider = transactionProvider;
    }

    @Override
    public <R, C extends Command<R>> R intercept(CommandMessage<R, C> commandMessage, CommandBusInterceptorChain<R, C> chain) {
        return transactionProvider.doInTransaction(() -> chain.proceed(commandMessage));
    }
}
