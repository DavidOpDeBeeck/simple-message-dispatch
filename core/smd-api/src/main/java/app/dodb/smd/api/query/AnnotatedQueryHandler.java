package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import app.dodb.smd.api.utils.parameterstrategy.AllowedParameterTypesStrategy;
import app.dodb.smd.api.utils.parameterstrategy.AtLeastOneParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.AtMostOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.ExactlyOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.parameterstrategy.ParameterValidationStrategy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static app.dodb.smd.api.utils.parameterstrategy.ParameterValidationStrategy.validateParameters;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

public record AnnotatedQueryHandler<R, Q extends Query<R>>(Provider<?> provider, Method method, Class<Q> queryType, MessageArgumentBinder<Q, QueryMessage<R, Q>> argumentBinder)
    implements QueryHandlerBehaviour<R, Q> {

    private static final List<ParameterValidationStrategy> PARAMETER_VALIDATION_STRATEGIES = List.of(
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Query.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new AllowedParameterTypesStrategy(
            Set.of(Query.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class)
        )
    );

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
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException("""
                Invalid query handler: method must be public.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        var parameters = method.getParameters();
        validateParameters(method, parameters, PARAMETER_VALIDATION_STRATEGIES);

        Class<C> queryType = (Class<C>) stream(parameters)
            .map(Parameter::getType)
            .filter(Query.class::isAssignableFrom)
            .findFirst()
            .orElseThrow();
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
