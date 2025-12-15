package app.dodb.smd.spring.test.scope;

import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

public class SMDTestScope implements Scope {

    public static final String SCOPE_SMD_TEST = "smd-test";

    @Override
    public Object get(String name, ObjectFactory<?> objectFactory) {
        return resolveContext().get(name, objectFactory);
    }

    @Override
    public Object remove(String name) {
        return resolveContext().remove(name);
    }

    @Override
    public void registerDestructionCallback(String name, Runnable callback) {
        resolveContext().registerDestructionCallback(name, callback);
    }

    @Override
    public Object resolveContextualObject(String key) {
        return resolveContext().resolveContextualObject(key);
    }

    @Override
    public String getConversationId() {
        return resolveContext().getConversationId();
    }

    private SMDTestScopeContext resolveContext() {
        return SMDTestScopeContext.getInstance();
    }
}