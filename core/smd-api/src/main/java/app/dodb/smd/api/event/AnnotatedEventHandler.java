package app.dodb.smd.api.event;

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
import app.dodb.smd.api.utils.handlerstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.PublicStrategy;
import app.dodb.smd.api.utils.handlerstrategy.ReturnTypeStrategy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.getAnnotationOnMethodOrClass;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

public record AnnotatedEventHandler<E extends Event>(Provider<?> provider, Method method, Class<E> eventType, int order, String processingGroup,
                                                     MessageArgumentBinder<E, EventMessage<E>> argumentBinder)
    implements EventHandlerBehaviour<E> {

    private static final List<HandlerValidationStrategy> HANDLER_VALIDATION_STRATEGIES = List.of(
        new PublicStrategy(),
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Event.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new AllowedParameterTypesStrategy(
            Set.of(Event.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class)
        ),
        new ReturnTypeStrategy(void.class)
    );

    public static ProcessingGroupRegistry from(Object object) {
        return from(new SingletonProvider<>(object));
    }

    public static ProcessingGroupRegistry from(Provider<?> provider) {
        Set<EventHandlerBehaviour<?>> handlers = new HashSet<>();
        Class<?> clazz = provider.getType();

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(EventHandler.class)) {
                var eventHandler = mapToEventHandler(provider, method);
                handlers.add(eventHandler);
            }
        }

        return ProcessingGroupRegistry.from(handlers);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Event> AnnotatedEventHandler<E> mapToEventHandler(Provider<?> provider, Method method) {
        validateHandler(method, HANDLER_VALIDATION_STRATEGIES);

        Optional<String> processingGroupOpt = getAnnotationOnMethodOrClass(method, ProcessingGroup.class).map(ProcessingGroup::value);
        if (processingGroupOpt.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid event handler: no processingConfig group found. Add @ProcessingGroup() annotation on the method or class.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        var parameters = method.getParameters();
        var eventType = (Class<E>) stream(parameters)
            .map(Parameter::getType)
            .filter(Event.class::isAssignableFrom)
            .findFirst()
            .orElseThrow();
        var order = method.getAnnotation(EventHandler.class).order();
        var processingGroup = processingGroupOpt.orElseThrow();
        return new AnnotatedEventHandler<>(provider, method, eventType, order, processingGroup, MessageArgumentBinder.fromMethodParameters(eventType, parameters));
    }

    public AnnotatedEventHandler {
        requireNonNull(provider);
        requireNonNull(method);
        requireNonNull(eventType);
        requireNonNull(processingGroup);
        requireNonNull(argumentBinder);
    }

    @Override
    public void handle(EventMessage<E> eventMessage) {
        try {
            method.invoke(provider.get(), argumentBinder.toArguments(eventMessage));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "@EventHandler " + logMethod(method);
    }
}
