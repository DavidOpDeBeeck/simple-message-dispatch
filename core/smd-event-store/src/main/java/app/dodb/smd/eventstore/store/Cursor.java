package app.dodb.smd.eventstore.store;

public interface Cursor<T> extends AutoCloseable {

    boolean hasNext();

    T next();
}
