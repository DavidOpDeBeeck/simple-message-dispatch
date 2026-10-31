package app.dodb.smd.eventstore.aggregate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.LoggingUtils.logMethods;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

record TargetAggregateIdAccessor(Method accessor) {

    TargetAggregateIdAccessor {
        requireNonNull(accessor);
    }

    static Optional<TargetAggregateIdAccessor> find(Class<?> commandType) {
        var accessors = stream(commandType.getMethods())
            .filter(method -> method.isAnnotationPresent(TargetAggregateId.class))
            .toList();
        if (accessors.isEmpty()) {
            return Optional.empty();
        }

        var accessor = accessors.getFirst();
        if (accessors.size() != 1
            || Modifier.isStatic(accessor.getModifiers())
            || accessor.getParameterCount() != 0
            || accessor.getReturnType() == Void.TYPE
            || !accessor.trySetAccessible()) {
            throw new IllegalArgumentException("""
                Invalid command: at most one public, non-static, zero-argument @TargetAggregateId method or record component may be declared.
                
                    Command:
                    %s
                
                    Accessors:
                    %s
                """.formatted(logClass(commandType), logMethods(accessors)));
        }
        return Optional.of(new TargetAggregateIdAccessor(accessor));
    }

    Object resolve(Object command) {
        Object aggregateId;
        try {
            aggregateId = accessor.invoke(command);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to resolve @TargetAggregateId: " + logMethod(accessor), e);
        }

        if (aggregateId == null) {
            throw new IllegalArgumentException("""
                Invalid command: @TargetAggregateId resolved to null.
                
                    Command:
                    %s
                
                    Accessor:
                    %s
                """.formatted(logClass(command.getClass()), logMethod(accessor)));
        }
        return aggregateId;
    }
}
