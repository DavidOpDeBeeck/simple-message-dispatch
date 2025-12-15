package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import com.google.common.collect.Sets;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logClasses;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public record AnnotatedQueryHandler<R, Q extends Query<R>>(Provider<?> provider, Method method, Class<Q> queryType) implements QueryHandlerBehaviour<R, Q> {

    public static QueryHandlerRegistry from(Object object) {
        return from(new SingletonProvider<>(object));
    }

    public static QueryHandlerRegistry from(Provider<?> provider) {
        Set<AnnotatedQueryHandler<?, ?>> handlers = new HashSet<>();
        Class<?> clazz = provider.getType();

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(QueryHandler.class)) {
                var queryHandler = mapToQueryHandler(provider, method);
                handlers.add(queryHandler);
            }
        }

        return new QueryHandlerRegistry(handlers);
    }

    @SuppressWarnings("unchecked")
    private static <R, C extends Query<R>> AnnotatedQueryHandler<R, C> mapToQueryHandler(Provider<?> provider, Method method) {
        Set<Class<?>> allParameterTypes = Set.of(method.getParameterTypes());
        Set<Class<?>> queryParameterTypes = allParameterTypes.stream()
            .filter(Query.class::isAssignableFrom)
            .collect(toSet());
        Set<Class<?>> otherParameterTypes = Sets.difference(allParameterTypes, queryParameterTypes);

        if (allParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid query handler: method must have at least one parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
        if (queryParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid query handler: method must include a parameter of type Query.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
        if (queryParameterTypes.size() > 1) {
            throw new IllegalArgumentException("""
                Invalid query handler: method must only include one Query as a parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        Set<Class<?>> allowedParameterTypes = Set.of(MessageId.class, Metadata.class);
        Set<Class<?>> invalidParameterTypes = Sets.difference(otherParameterTypes, allowedParameterTypes);
        if (!invalidParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid query handler: unsupported parameter types found.
                
                    Method:
                    %s
                
                    Allowed:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(method), logClasses(allowedParameterTypes), logClasses(invalidParameterTypes)));
        }

        Class<?> queryType = queryParameterTypes.iterator().next();
        Type queryReturnType = resolveGenericType(queryType, Query.class);
        Type methodReturnType = method.getGenericReturnType();
        if (!Objects.equals(methodReturnType, queryReturnType)) {
            throw new IllegalArgumentException("""
                Invalid query handler: return type mismatch.
                
                    Method:
                    %s
                
                    Expected:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(method), logClass(queryReturnType), logClass(methodReturnType)));
        }

        return new AnnotatedQueryHandler<>(provider, method, (Class<C>) queryType);
    }

    public AnnotatedQueryHandler {
        requireNonNull(provider);
        requireNonNull(method);
        requireNonNull(queryType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public R handle(QueryMessage<R, Q> queryMessage) {
        try {
            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] parameters = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (MessageId.class.isAssignableFrom(parameterType)) {
                    parameters[i] = queryMessage.getMessageId();
                }
                if (Metadata.class.isAssignableFrom(parameterType)) {
                    parameters[i] = queryMessage.getMetadata();
                }
                if (Query.class.isAssignableFrom(parameterType)) {
                    parameters[i] = queryMessage.getPayload();
                }
            }
            return (R) method.invoke(provider.get(), parameters);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
