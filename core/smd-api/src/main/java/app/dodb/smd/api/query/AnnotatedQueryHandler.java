package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import com.google.common.collect.Sets;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logClasses;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static app.dodb.smd.api.utils.TypeUtils.unrelatedTypes;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public record AnnotatedQueryHandler<R, Q extends Query<R>>(Provider<?> provider, Method method, Class<Q> queryType, MessageArgumentBinder<Q, QueryMessage<R, Q>> argumentBinder)
    implements QueryHandlerBehaviour<R, Q> {

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

        Set<Class<?>> allowedParameterTypes = Set.of(MessageId.class, Metadata.class, Principal.class, Instant.class, String.class);
        Set<Class<?>> invalidParameterTypes = unrelatedTypes(otherParameterTypes, allowedParameterTypes);
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

        var parameters = method.getParameters();
        for (Parameter parameter : parameters) {
            if (String.class.isAssignableFrom(parameter.getType()) && !parameter.isAnnotationPresent(MetadataValue.class)) {
                throw new IllegalArgumentException("""
                    Invalid query handler: metadata value parameter must be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            }
            if (parameter.isAnnotationPresent(MetadataValue.class) && !String.class.isAssignableFrom(parameter.getType())) {
                throw new IllegalArgumentException("""
                    Invalid query handler: only parameters of type String can be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            }
        }

        Class<C> queryType = (Class<C>) queryParameterTypes.iterator().next();
        Type queryReturnType = resolveGenericType(queryType, Query.class);
        Type methodReturnType = method.getGenericReturnType();
        if (!haveSameBounds(methodReturnType, queryReturnType)) {
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

        return new AnnotatedQueryHandler<>(provider, method, queryType, fromMethodParameters(queryType, parameters));
    }

    public AnnotatedQueryHandler {
        requireNonNull(provider);
        requireNonNull(method);
        requireNonNull(queryType);
        requireNonNull(argumentBinder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public R handle(QueryMessage<R, Q> queryMessage) {
        try {
            return (R) method.invoke(provider.get(), argumentBinder.toArguments(queryMessage));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
