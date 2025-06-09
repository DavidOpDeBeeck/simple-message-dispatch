package app.dodb.smd.api.framework;

import java.lang.reflect.InvocationTargetException;

public class ConstructorBasedObjectCreator implements ObjectCreator {

    public static final ConstructorBasedObjectCreator INSTANCE = new ConstructorBasedObjectCreator();

    @Override
    public <T> Provider<T> create(Class<T> clazz) {
        try {
            T instance = clazz.getDeclaredConstructor().newInstance();
            return new SingletonProvider<>(instance);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
