package app.dodb.smd.api.framework;

public interface ObjectCreator {

    <T> Provider<T> create(Class<T> clazz);
}
