package app.dodb.smd.test;

import app.dodb.smd.api.framework.TransactionProvider;

import java.util.function.Supplier;

public class NoOpTransactionProvider implements TransactionProvider {

    @Override
    public void defer(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T> T doInTransaction(Supplier<T> supplier) {
        return supplier.get();
    }

    @Override
    public void doInTransaction(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T> T doInNewTransaction(Supplier<T> supplier) {
        return supplier.get();
    }

    @Override
    public void doInNewTransaction(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
        return supplier.get();
    }
}
