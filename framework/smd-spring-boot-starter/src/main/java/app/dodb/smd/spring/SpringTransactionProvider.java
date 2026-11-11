package app.dodb.smd.spring;

import app.dodb.smd.api.framework.TransactionProvider;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

public class SpringTransactionProvider implements TransactionProvider {

    private static final ThreadLocal<TransactionContext> TRANSACTION_CONTEXT = new ThreadLocal<>();

    @Override
    @Transactional
    public void defer(Runnable runnable) {
        var context = TRANSACTION_CONTEXT.get();
        if (context != null) {
            context.addDeferredWork(runnable);
            return;
        }
        runnable.run();
    }

    @Override
    public <T> T resource(Object key, Supplier<T> initial) {
        var context = TRANSACTION_CONTEXT.get();
        if (context != null) {
            return context.resource(key, initial);
        }
        return initial.get();
    }

    @Override
    @Transactional
    public <T> T doInTransaction(Supplier<T> supplier) {
        if (TRANSACTION_CONTEXT.get() != null) {
            return supplier.get();
        }
        return callInNewContext(supplier);
    }

    @Override
    @Transactional
    public void doInTransaction(Runnable runnable) {
        if (TRANSACTION_CONTEXT.get() != null) {
            runnable.run();
            return;
        }
        runInNewContext(runnable);
    }

    @Override
    @Transactional(propagation = REQUIRES_NEW)
    public <T> T doInNewTransaction(Supplier<T> supplier) {
        return callInNewContext(supplier);
    }

    @Override
    @Transactional(propagation = REQUIRES_NEW)
    public void doInNewTransaction(Runnable runnable) {
        runInNewContext(runnable);
    }

    @Override
    @Transactional(readOnly = true)
    public <T> T doInReadOnlyTransaction(Supplier<T> supplier) {
        return supplier.get();
    }

    private <T> T callInNewContext(Supplier<T> supplier) {
        var outerContext = TRANSACTION_CONTEXT.get();
        var context = new TransactionContext();
        TRANSACTION_CONTEXT.set(context);
        try {
            var result = supplier.get();
            context.runDeferredWork();
            return result;
        } finally {
            if (outerContext == null) {
                TRANSACTION_CONTEXT.remove();
            } else {
                TRANSACTION_CONTEXT.set(outerContext);
            }
        }
    }

    private void runInNewContext(Runnable runnable) {
        callInNewContext(() -> {
            runnable.run();
            return null;
        });
    }

    private static class TransactionContext {

        private final List<Runnable> deferredWork = new ArrayList<>();
        private final Map<Object, Object> resources = new HashMap<>();

        void addDeferredWork(Runnable work) {
            deferredWork.add(work);
        }

        @SuppressWarnings("unchecked")
        <T> T resource(Object key, Supplier<T> initial) {
            return (T) resources.computeIfAbsent(key, _ -> initial.get());
        }

        void runDeferredWork() {
            for (Runnable runnable : deferredWork) {
                runnable.run();
            }
        }
    }
}
