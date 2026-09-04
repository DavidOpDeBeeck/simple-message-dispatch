package app.dodb.smd.eventstore.storage;

public interface Cursor<T> extends AutoCloseable {

    boolean hasNext();

    T next();

    @Override
    void close();
}
