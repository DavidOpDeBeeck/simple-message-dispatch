package app.dodb.smd.api.framework;

import java.util.function.Supplier;

public interface TransactionProvider {

    void defer(Runnable runnable);

    <T> T doInTransaction(Supplier<T> supplier);

    void doInTransaction(Runnable runnable);

    <T> T doInNewTransaction(Supplier<T> supplier);

    void doInNewTransaction(Runnable runnable);

    <T> T doInReadOnlyTransaction(Supplier<T> supplier);
}
