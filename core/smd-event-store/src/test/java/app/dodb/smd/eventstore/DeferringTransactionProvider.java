package app.dodb.smd.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// Joins an outer transaction and runs deferred work when the outermost transaction completes; its resources end with it, like SpringTransactionProvider.
public class DeferringTransactionProvider implements TransactionProvider {

    private List<Runnable> deferredWork;
    private Map<Object, Object> resources;

    @Override
    public void defer(Runnable runnable) {
        if (deferredWork == null) {
            runnable.run();
            return;
        }
        deferredWork.add(runnable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T resource(Object key, Supplier<T> initial) {
        if (resources == null) {
            return initial.get();
        }
        return (T) resources.computeIfAbsent(key, _ -> initial.get());
    }

    @Override
    public <T> T doInTransaction(Supplier<T> supplier) {
        if (deferredWork != null) {
            return supplier.get();
        }

        deferredWork = new ArrayList<>();
        resources = new HashMap<>();
        try {
            var result = supplier.get();
            deferredWork.forEach(Runnable::run);
            return result;
        } finally {
            deferredWork = null;
            resources = null;
        }
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
        throw new UnsupportedOperationException();
    }

    @Override
    public void doInNewTransaction(Runnable runnable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
        throw new UnsupportedOperationException();
    }
}
