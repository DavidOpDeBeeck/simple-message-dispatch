package app.dodb.smd.api.event;

import app.dodb.smd.api.framework.Provider;
import app.dodb.smd.api.framework.SingletonProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import app.dodb.smd.api.utils.TypeUtils;
import com.google.common.collect.Sets;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logClasses;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.getAnnotationOnMethodOrClass;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

public record AnnotatedEventHandler<E extends Event>(Provider<?> provider, Method method, Class<E> eventType, int order, String processingGroup,
                                                     MessageArgumentBinder<E, EventMessage<E>> argumentBinder)
    implements EventHandlerBehaviour<E> {

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

        Set<Class<?>> allParameterTypes = Set.of(method.getParameterTypes());
        Set<Class<?>> eventParameterTypes = allParameterTypes.stream()
            .filter(Event.class::isAssignableFrom)
            .collect(toSet());
        Set<Class<?>> otherParameterTypes = Sets.difference(allParameterTypes, eventParameterTypes);

        if (allParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid event handler: method must have at least one parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
        if (eventParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid event handler: method must include a parameter of type Event.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
        if (eventParameterTypes.size() > 1) {
            throw new IllegalArgumentException("""
                Invalid event handler: method must only include one Event as a parameter.
                
                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        Set<Class<?>> allowedParameterTypes = Set.of(MessageId.class, Metadata.class, Principal.class, Instant.class, String.class);
        Set<Class<?>> invalidParameterTypes = TypeUtils.unrelatedTypes(otherParameterTypes, allowedParameterTypes);
        if (!invalidParameterTypes.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid event handler: unsupported parameter types found.
                
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
                    Invalid event handler: metadata value parameter must be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            }
            if (parameter.isAnnotationPresent(MetadataValue.class) && !String.class.isAssignableFrom(parameter.getType())) {
                throw new IllegalArgumentException("""
                    Invalid event handler: only parameters of type String can be annotated with @MetadataValue.
                    
                        Method:
                        %s
                    
                        Parameter:
                        %s
                    """.formatted(logMethod(method), parameter.getName()));
            }
        }

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

        var eventType = (Class<E>) eventParameterTypes.iterator().next();
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
