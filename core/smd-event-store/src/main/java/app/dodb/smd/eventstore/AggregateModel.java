package app.dodb.smd.eventstore;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.EventSourcingHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.LoggingUtils.logMethods;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;

final class AggregateModel<A> {

    private final Class<A> aggregateClass;
    private final String aggregateType;
    private final Constructor<A> replayConstructor;
    private final Field idField;
    private final List<Method> eventSourcingHandlers;
    private final List<Constructor<A>> creationHandlers;
    private final List<Method> commandHandlers;
    private final ConcurrentMap<Class<?>, Optional<Method>> eventSourcingHandlersByEventType = new ConcurrentHashMap<>();

    private AggregateModel(Class<A> aggregateClass,
                           String aggregateType,
                           Constructor<A> replayConstructor,
                           Field idField,
                           List<Method> eventSourcingHandlers,
                           List<Constructor<A>> creationHandlers,
                           List<Method> commandHandlers) {
        this.aggregateClass = requireNonNull(aggregateClass);
        this.aggregateType = requireNonNull(aggregateType);
        this.replayConstructor = requireNonNull(replayConstructor);
        this.idField = requireNonNull(idField);
        this.eventSourcingHandlers = List.copyOf(eventSourcingHandlers);
        this.creationHandlers = List.copyOf(creationHandlers);
        this.commandHandlers = List.copyOf(commandHandlers);
    }

    static <A> AggregateModel<A> inspect(Class<A> aggregateClass) {
        return new AggregateModel<>(
            aggregateClass,
            findAggregateType(aggregateClass),
            findReplayConstructor(aggregateClass),
            findIdField(aggregateClass),
            findEventSourcingHandlers(aggregateClass),
            findCreationHandlers(aggregateClass),
            findCommandHandlers(aggregateClass)
        );
    }

    Class<A> aggregateClass() {
        return aggregateClass;
    }

    String aggregateType() {
        return aggregateType;
    }

    Type aggregateIdType() {
        return idField.getGenericType();
    }

    List<Constructor<A>> creationHandlers() {
        return creationHandlers;
    }

    List<Method> commandHandlers() {
        return commandHandlers;
    }

    A newInstance() {
        try {
            return replayConstructor.newInstance();
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate aggregate: " + logClass(aggregateClass), e);
        }
    }

    void applyEvent(A aggregate, Event event) {
        var handler = eventSourcingHandlersByEventType.computeIfAbsent(event.getClass(), this::findEventSourcingHandler);
        if (handler.isEmpty()) {
            return;
        }

        try {
            handler.get().invoke(aggregate, event);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to invoke event sourcing handler: " + logMethod(handler.get()), e);
        }
    }

    Optional<Object> aggregateIdValue(A aggregate) {
        try {
            return Optional.ofNullable(idField.get(aggregate));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read @AggregateId field: %s#%s".formatted(logClass(aggregateClass), idField.getName()), e);
        }
    }

    Optional<String> aggregateId(A aggregate) {
        return aggregateIdValue(aggregate).map(Object::toString);
    }

    private Optional<Method> findEventSourcingHandler(Class<?> eventType) {
        return eventSourcingHandlers.stream()
            .filter(handler -> handler.getParameterTypes()[0].isAssignableFrom(eventType))
            .findFirst();
    }

    private static String findAggregateType(Class<?> aggregateClass) {
        var aggregate = aggregateClass.getAnnotation(Aggregate.class);
        if (aggregate == null || aggregate.value().isBlank()) {
            throw new IllegalArgumentException("""
                Invalid aggregate: the class must be annotated with @Aggregate and a non-blank, stable type name.

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

    private static List<Method> findEventSourcingHandlers(Class<?> aggregateClass) {
        var handlers = stream(aggregateClass.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventSourcingHandler.class))
            .toList();

        for (var handler : handlers) {
            if (Modifier.isStatic(handler.getModifiers())
                || handler.getParameterCount() != 1
                || !Event.class.isAssignableFrom(handler.getParameterTypes()[0])
                || handler.getReturnType() != Void.TYPE) {
                throw new IllegalArgumentException("""
                    Invalid event sourcing handler: the method must be non-static, return void, and take exactly one event parameter.

                        Method:
                        %s
                    """.formatted(logMethod(handler)));
            }
            if (!handler.trySetAccessible()) {
                throw new IllegalArgumentException("Invalid event sourcing handler: the method is not accessible: " + logMethod(handler));
            }

            var overlappingHandlers = handlers.stream()
                .filter(other -> handler.getParameterTypes()[0].isAssignableFrom(other.getParameterTypes()[0]))
                .toList();
            if (overlappingHandlers.size() > 1) {
                throw new IllegalArgumentException("""
                    Ambiguous event sourcing handlers found:

                        Event:
                        %s

                        Methods:
                        %s
                    """.formatted(logClass(handler.getParameterTypes()[0]), logMethods(overlappingHandlers)));
            }
        }
        return handlers;
    }

    @SuppressWarnings("unchecked")
    private static <A> List<Constructor<A>> findCreationHandlers(Class<A> aggregateClass) {
        var handlers = stream(aggregateClass.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(CommandHandler.class))
            .map(constructor -> (Constructor<A>) constructor)
            .toList();
        if (handlers.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid aggregate: at least one @CommandHandler constructor must create the aggregate.

                    Aggregate:
                    %s
                """.formatted(logClass(aggregateClass)));
        }
        return handlers;
    }

    private static List<Method> findCommandHandlers(Class<?> aggregateClass) {
        return stream(aggregateClass.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(CommandHandler.class))
            .toList();
    }
}
