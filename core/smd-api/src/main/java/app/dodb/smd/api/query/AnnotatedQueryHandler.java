package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import app.dodb.smd.api.utils.handlerstrategy.AllowedParameterTypesStrategy;
import app.dodb.smd.api.utils.handlerstrategy.AtLeastOneParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.AtMostOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.ExactlyOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy;
import app.dodb.smd.api.utils.handlerstrategy.MessageReturnTypeStrategy;
import app.dodb.smd.api.utils.handlerstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.PublicStrategy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

public record AnnotatedQueryHandler<R, Q extends Query<R>>(Provider<?> provider, Method method, Class<Q> queryType, MessageArgumentBinder<Q, QueryMessage<R, Q>> argumentBinder)
    implements QueryHandlerBehaviour<R, Q> {

    private static final List<HandlerValidationStrategy> HANDLER_VALIDATION_STRATEGIES = List.of(
        new PublicStrategy(),
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Query.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new AllowedParameterTypesStrategy(
            Set.of(Query.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class)
        ),
        new MessageReturnTypeStrategy(Query.class)
    );

    public static QueryHandlerRegistry from(Object object) {
        return from(new SingletonProvider<>(object));
    }

    public static QueryHandlerRegistry from(Provider<?> provider) {
        Set<QueryHandlerBehaviour<?, ?>> handlers = new HashSet<>();
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
        validateHandler(method, HANDLER_VALIDATION_STRATEGIES);

        var parameters = method.getParameters();
        Class<C> queryType = (Class<C>) stream(parameters)
            .map(Parameter::getType)
            .filter(Query.class::isAssignableFrom)
            .findFirst()
            .orElseThrow();

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

    @Override
    public String toString() {
        return "@QueryHandler " + logMethod(method);
    }
}
