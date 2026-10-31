package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
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
import app.dodb.smd.api.utils.handlerstrategy.NonStaticStrategy;
import app.dodb.smd.api.utils.handlerstrategy.ReturnTypeStrategy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.LoggingUtils.logMethods;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;

record AggregateModel<A>(Class<A> aggregateClass, String aggregateType, Constructor<A> replayConstructor, Field idField,
                         List<EventSourcingHandlerMethod> eventSourcingHandlers) {

    private static final List<HandlerValidationStrategy> EVENT_SOURCING_HANDLER_STRATEGIES = List.of(
        new NonStaticStrategy(),
        new ReturnTypeStrategy(void.class),
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Event.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new AllowedParameterTypesStrategy(Set.of(Event.class, MessageId.class, Metadata.class, Principal.class, Instant.class, String.class))
    );

    AggregateModel {
        requireNonNull(aggregateClass);
        requireNonNull(aggregateType);
        requireNonNull(replayConstructor);
        requireNonNull(idField);
        requireNonNull(eventSourcingHandlers);
    }

    static <A> AggregateModel<A> inspect(Class<A> aggregateClass) {
        return new AggregateModel<>(
            aggregateClass,
            findAggregateType(aggregateClass),
            findReplayConstructor(aggregateClass),
            findIdField(aggregateClass),
            findEventSourcingHandlers(aggregateClass)
        );
    }

    Type aggregateIdType() {
        return idField.getGenericType();
    }

    Optional<Object> aggregateId(A aggregate) {
        try {
            return Optional.ofNullable(idField.get(aggregate));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read @AggregateId field: %s#%s".formatted(logClass(aggregateClass), idField.getName()), e);
        }
    }

    A replay(List<EventMessage<?>> history) {
        var aggregate = newInstance();
        history.forEach(eventMessage -> apply(aggregate, eventMessage));
        return aggregate;
    }

    void apply(A aggregate, EventMessage<?> eventMessage) {
        var eventType = eventMessage.payload().getClass();
        for (var handler : eventSourcingHandlers) {
            if (handler.eventType().isAssignableFrom(eventType)) {
                handler.apply(aggregate, eventMessage);
            }
        }
    }

    private A newInstance() {
        try {
            return replayConstructor.newInstance();
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate aggregate: " + logClass(aggregateClass), e);
        }
    }

    private static String findAggregateType(Class<?> aggregateClass) {
        var aggregate = aggregateClass.getAnnotation(Aggregate.class);
        if (aggregate == null) {
            throw new IllegalArgumentException("""
                Invalid aggregate: the class must be annotated with @Aggregate and a stable type name.
                
                    Aggregate:
                    %s
                """.formatted(logClass(aggregateClass)));
        }
        return aggregate.value();
    }

    private static <A> Constructor<A> findReplayConstructor(Class<A> aggregateClass) {
        try {
            var constructor = aggregateClass.getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw new IllegalArgumentException("Invalid aggregate: the no-argument constructor is not accessible: " + logClass(aggregateClass));
            }
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("""
                Invalid aggregate: a no-argument constructor is required to replay the aggregate from its events.
                
                    Aggregate:
                    %s
                """.formatted(logClass(aggregateClass)), e);
        }
    }

    private static Field findIdField(Class<?> aggregateClass) {
        var idFields = stream(aggregateClass.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(AggregateId.class))
            .toList();
        if (idFields.size() != 1 || Modifier.isStatic(idFields.getFirst().getModifiers())) {
            throw new IllegalArgumentException("""
                Invalid aggregate: exactly one non-static @AggregateId field must be declared.
                
                    Aggregate:
                    %s
                
                    Fields:
                    %s
                """.formatted(logClass(aggregateClass), idFields.stream().map(Field::getName).toList()));
        }

        var idField = idFields.getFirst();
        if (!idField.trySetAccessible()) {
            throw new IllegalArgumentException("Invalid aggregate: @AggregateId field is not accessible: %s#%s".formatted(logClass(aggregateClass), idField.getName()));
        }
        return idField;
    }

    private static List<EventSourcingHandlerMethod> findEventSourcingHandlers(Class<?> aggregateClass) {
        var handlers = stream(aggregateClass.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventSourcingHandler.class))
            .map(AggregateModel::eventSourcingHandler)
            .sorted(comparingInt(EventSourcingHandlerMethod::order))
            .toList();

        for (var handler : handlers) {
            var overlappingHandlersWithSameOrder = handlers.stream()
                .filter(other -> handler.eventType().isAssignableFrom(other.eventType()))
                .filter(other -> handler.order() == other.order())
                .map(EventSourcingHandlerMethod::method)
                .toList();
            if (overlappingHandlersWithSameOrder.size() > 1) {
                throw new IllegalArgumentException("""
                    Event sourcing handlers with same order found:
                    
                        Event:
                        %s
                    
                        Methods:
                        %s
                    """.formatted(logClass(handler.eventType()), logMethods(overlappingHandlersWithSameOrder)));
            }
        }
        return handlers;
    }

    @SuppressWarnings("unchecked")
    private static EventSourcingHandlerMethod eventSourcingHandler(Method method) {
        validateHandler(method, EVENT_SOURCING_HANDLER_STRATEGIES);
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("Invalid event sourcing handler: the method is not accessible: " + logMethod(method));
        }

        var parameters = method.getParameters();
        Class<Event> eventType = stream(parameters)
            .map(Parameter::getType)
            .filter(Event.class::isAssignableFrom)
            .map(type -> (Class<Event>) type)
            .findFirst()
            .orElseThrow();
        var order = method.getAnnotation(EventSourcingHandler.class).order();
        return new EventSourcingHandlerMethod(method, eventType, order, fromMethodParameters(eventType, parameters));
    }

    private record EventSourcingHandlerMethod(Method method, Class<? extends Event> eventType, int order, MessageArgumentBinder<Event, EventMessage<Event>> argumentBinder) {

        private EventSourcingHandlerMethod {
            requireNonNull(method);
            requireNonNull(eventType);
            requireNonNull(argumentBinder);
        }

        @SuppressWarnings("unchecked")
        void apply(Object aggregate, EventMessage<?> eventMessage) {
            try {
                method.invoke(aggregate, argumentBinder.toArguments((EventMessage<Event>) eventMessage));
            } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to invoke event sourcing handler: " + logMethod(method), e);
            }
        }
    }
}
