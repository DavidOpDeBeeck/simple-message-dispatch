package app.dodb.smd.spring;

import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.Provider;
import org.springframework.context.ApplicationContext;

import static java.util.Objects.requireNonNull;

public class SpringObjectCreator implements ObjectCreator {

    private final ApplicationContext applicationContext;

    public SpringObjectCreator(ApplicationContext applicationContext) {
        this.applicationContext = requireNonNull(applicationContext);
    }

    @Override
    public <T> Provider<T> create(Class<T> clazz) {
        return new Provider<>() {
            @Override
            public T get() {
                return applicationContext.getBean(clazz);
            }

            @Override
            public Class<?> getType() {
                return clazz;
            }
        };
    }
}
