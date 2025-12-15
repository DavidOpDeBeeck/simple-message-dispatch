package app.dodb.smd.api.framework;

public interface Provider<T> {

    T get();

    Class<?> getType();
}
