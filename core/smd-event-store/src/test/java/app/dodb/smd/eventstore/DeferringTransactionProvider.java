package app.dodb.smd.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

class DeferringTransactionProvider implements TransactionProvider {

    private final ThreadLocal<List<Runnable>> deferredWork = new ThreadLocal<>();

    @Override
    public void defer(Runnable runnable) {
        var work = deferredWork.get();
        if (work == null) {
            runnable.run();
            return;
        }
        work.add(runnable);
    }

    @Override
    public <T> T doInTransaction(Supplier<T> supplier) {
        return deferredWork.get() == null ? doInNewTransaction(supplier) : supplier.get();
    }

    @Override
    public void doInTransaction(Runnable runnable) {
        doInTransaction(() -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public <T> T doInNewTransaction(Supplier<T> supplier) {
        var outerWork = deferredWork.get();
        var work = new ArrayList<Runnable>();
        deferredWork.set(work);
        try {
            var result = supplier.get();
            work.forEach(Runnable::run);
            return result;
        } finally {
            deferredWork.set(outerWork);
        }
    }

    @Override
    public void doInNewTransaction(Runnable runnable) {
        doInNewTransaction(() -> {
            runnable.run();
            return null;
        });
    }

    @Override
    public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
        return supplier.get();
    }
}
