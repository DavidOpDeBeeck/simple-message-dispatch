package app.dodb.smd.api.event;

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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.getAnnotationOnMethodOrClass;
import static app.dodb.smd.api.utils.parameterstrategy.ParameterValidationStrategy.validateParameters;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

public record AnnotatedEventHandler<E extends Event>(Provider<?> provider, Method method, Class<E> eventType, int order, String processingGroup,
                                                     MessageArgumentBinder<E, EventMessage<E>> argumentBinder)
    implements EventHandlerBehaviour<E> {

    private static final List<ParameterValidationStrategy> PARAMETER_VALIDATION_STRATEGIES = List.of(
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Event.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new AllowedParameterTypesStrategy(
            Set.of(Event.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class)
        )
    );

    public static ProcessingGroupRegistry from(Object object) {
        return from(new SingletonProvider<>(object));
    }

    public static ProcessingGroupRegistry from(Provider<?> provider) {
        Set<AnnotatedEventHandler<?>> handlers = new HashSet<>();
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
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException("""
                Invalid event handler: method must be public.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        var parameters = method.getParameters();
        validateParameters(method, parameters, PARAMETER_VALIDATION_STRATEGIES);

        Class<?> methodReturnType = method.getReturnType();
        if (!Void.TYPE.equals(methodReturnType)) {
            throw new IllegalArgumentException("""
                Invalid event handler: return type mismatch.
                
                    Method:
                    %s
                
                    Expected:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(method), logClass(Void.TYPE), logClass(methodReturnType)));
        }

        Optional<String> processingGroupOpt = getAnnotationOnMethodOrClass(method, ProcessingGroup.class).map(ProcessingGroup::value);
        if (processingGroupOpt.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid event handler: no processingConfig group found. Add @ProcessingGroup() annotation on the method or class.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

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
}
