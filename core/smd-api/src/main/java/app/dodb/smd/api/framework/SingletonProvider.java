package app.dodb.smd.api.framework;

import static java.util.Objects.requireNonNull;

public class SingletonProvider<T> implements Provider<T> {

    private final T instance;

    public SingletonProvider(T instance) {
        this.instance = requireNonNull(instance);
    }

    @Override
    public T get() {
        return instance;
    }

    @Override
    public Class<?> getType() {
        return instance.getClass();
    }
}
