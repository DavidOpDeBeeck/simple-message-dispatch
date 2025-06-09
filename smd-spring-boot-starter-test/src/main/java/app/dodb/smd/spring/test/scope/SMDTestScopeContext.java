package app.dodb.smd.spring.test.scope;

import org.springframework.beans.factory.ObjectFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.lang.String.format;
import static java.lang.ThreadLocal.withInitial;
import static java.util.Objects.requireNonNull;

public class SMDTestScopeContext {

    private static final ThreadLocal<SMDTestScopeContext> contextHolder = withInitial(SMDTestScopeContext::new);

    static SMDTestScopeContext getInstance() {
        return contextHolder.get();
    }

    private final Map<String, Object> objectByName = new HashMap<>();
    private final Map<String, Runnable> destructionCallbackByName = new HashMap<>();

    private String activeIdentifier;

    void start(String identifier) {
        requireActiveIdentifierToEqual(null);
        activeIdentifier = requireNonNull(identifier);
    }

    void stop(String identifier) {
        requireActiveIdentifierToEqual(identifier);
        destructionCallbackByName.values().forEach(Runnable::run);
        contextHolder.remove();
        activeIdentifier = null;
    }

    Object get(String name, ObjectFactory<?> objectFactory) {
        requireActiveIdentifier();
        if (!objectByName.containsKey(name)) {
            objectByName.put(name, objectFactory.getObject());
        }
        return objectByName.get(name);
    }

    Object remove(String name) {
        requireActiveIdentifier();
        destructionCallbackByName.remove(name);
        return objectByName.remove(name);
    }

    void registerDestructionCallback(String name, Runnable callback) {
        requireActiveIdentifier();
        destructionCallbackByName.put(name, callback);
    }

    Object resolveContextualObject(String key) {
        requireActiveIdentifier();
        return null;
    }

    String getConversationId() {
        requireActiveIdentifier();
        return "smd-test-" + activeIdentifier;
    }

    private void requireActiveIdentifierToEqual(String identifier) {
        if (Objects.equals(identifier, activeIdentifier)) {
            return;
        }
        throw new IllegalArgumentException(format("SMD test identifier assertion failed. " +
            "Expected: <%s> but was <%s>", identifier, activeIdentifier));
    }

    private void requireActiveIdentifier() {
        if (activeIdentifier == null) {
            throw new IllegalStateException("SMD test scoped beans can only be used while a test is being executed");
        }
    }
}
