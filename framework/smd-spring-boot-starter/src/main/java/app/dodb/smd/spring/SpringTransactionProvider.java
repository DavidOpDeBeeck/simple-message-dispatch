package app.dodb.smd.spring;

import app.dodb.smd.api.framework.TransactionProvider;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static java.lang.ScopedValue.newInstance;
import static java.lang.ScopedValue.where;
import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

public class SpringTransactionProvider implements TransactionProvider {

    private static final ScopedValue<TransactionContext> TRANSACTION_CONTEXT = newInstance();

    @Override
    public void defer(Runnable runnable) {
        if (!TRANSACTION_CONTEXT.isBound()) {
            throw new IllegalStateException("No active transaction context present");
        }
        TRANSACTION_CONTEXT.get().addDeferredWork(runnable);
    }

    @Override
    @Transactional
    public <T> T doInTransaction(Supplier<T> supplier) {
        if (TRANSACTION_CONTEXT.isBound()) {
            return supplier.get();
        }

        return callInNewContext(supplier);
    }

    @Override
    @Transactional
    public void doInTransaction(Runnable runnable) {
        if (TRANSACTION_CONTEXT.isBound()) {
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
        return where(TRANSACTION_CONTEXT, new TransactionContext()).call(() -> {
            var result = supplier.get();
            TRANSACTION_CONTEXT.get().runDeferredWork();
            return result;
        });
    }

    private void runInNewContext(Runnable runnable) {
        where(TRANSACTION_CONTEXT, new TransactionContext()).run(() -> {
            runnable.run();
            TRANSACTION_CONTEXT.get().runDeferredWork();
        });
    }

    private static class TransactionContext {

        private final List<Runnable> deferredWork = new ArrayList<>();

        void addDeferredWork(Runnable work) {
            deferredWork.add(work);
        }

        void runDeferredWork() {
            for (Runnable runnable : deferredWork) {
                runnable.run();
            }
        }
    }
}
