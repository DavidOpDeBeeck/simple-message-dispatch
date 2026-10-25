package app.dodb.smd.eventstore;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandlerBehaviour;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.MessageArgumentBinder;
import app.dodb.smd.eventstore.aggregate.AggregateEvents;
import app.dodb.smd.eventstore.aggregate.AggregateNotFoundException;
import app.dodb.smd.eventstore.aggregate.TargetAggregateId;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.serialization.EventSerializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.LoggingUtils.logMethods;
import static app.dodb.smd.api.utils.MessageArgumentBinder.fromMethodParameters;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Stream.concat;

final class AggregateCommandHandler<R, C extends Command<R>, A> implements CommandHandlerBehaviour<R, C> {

    private static final List<Class<?>> CONTEXT_PARAMETER_TYPES = List.of(MessageId.class, Metadata.class, Principal.class, Instant.class);

    private final AggregateModel<A> model;
    private final Method method;
    private final Class<C> commandType;
    private final MessageArgumentBinder<C, CommandMessage<R, C>> argumentBinder;
    private final OptionalInt aggregateEventsParameterIndex;
    private final Optional<Method> targetAggregateIdAccessor;
    private final TransactionProvider transactionProvider;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;
    private final EventPublisher eventPublisher;

    private AggregateCommandHandler(AggregateModel<A> model,
                                    Method method,
                                    Class<C> commandType,
                                    OptionalInt aggregateEventsParameterIndex,
                                    Optional<Method> targetAggregateIdAccessor,
                                    EventStoreConfig config,
                                    EventPublisher eventPublisher) {
        this.model = requireNonNull(model);
        this.method = requireNonNull(method);
        this.commandType = requireNonNull(commandType);
        this.argumentBinder = fromMethodParameters(commandType, method.getParameters());
        this.aggregateEventsParameterIndex = requireNonNull(aggregateEventsParameterIndex);
        this.targetAggregateIdAccessor = requireNonNull(targetAggregateIdAccessor);
        this.transactionProvider = config.getTransactionProvider();
        this.eventStorage = config.getEventStorage();
        this.eventSerializer = config.getEventSerializer();
        this.eventPublisher = requireNonNull(eventPublisher);
    }

    @SuppressWarnings("unchecked")
    static <A> AggregateCommandHandler<?, ?, A> from(AggregateModel<A> model, Method method, EventStoreConfig config, EventPublisher eventPublisher) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: the method must be public and non-static.

                    Method:
                    %s
                """.formatted(logMethod(method)));
        }

        var parameters = method.getParameters();
        validateParameters(method, parameters);

        var commandType = (Class<? extends Command<?>>) stream(parameters)
            .map(Parameter::getType)
            .filter(Command.class::isAssignableFrom)
            .findFirst()
            .orElseThrow();
        var commandReturnType = resolveGenericType(commandType, Command.class);
        var methodReturnType = method.getGenericReturnType();
        if (!haveSameBounds(methodReturnType, commandReturnType)) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: return type mismatch.

                    Method:
                    %s

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(logMethod(method), logClass(commandReturnType), logClass(methodReturnType)));
        }

        var aggregateEventsParameterIndex = IntStream.range(0, parameters.length)
            .filter(index -> parameters[index].getType() == AggregateEvents.class)
            .findFirst();
        return new AggregateCommandHandler<>(model, method, (Class) commandType, aggregateEventsParameterIndex, findTargetAggregateIdAccessor(commandType),
            config, eventPublisher);
    }

    @Override
    public Class<C> commandType() {
        return commandType;
    }

    @Override
    public Method method() {
        return method;
    }

    @Override
    public R handle(CommandMessage<R, C> commandMessage) {
        return transactionProvider.doInTransaction(() -> {
            var aggregate = targetAggregateId(commandMessage.payload())
                .map(this::load)
                .orElseGet(() -> EventSourcedAggregate.create(model));

            var result = invoke(aggregate, commandMessage);
            append(aggregate, aggregate.completeHandling());
            return result;
        });
    }

    private EventSourcedAggregate<A> load(String aggregateId) {
        var history = eventStorage.loadAggregateStream(model.aggregateType(), aggregateId);
        if (history.isEmpty()) {
            throw new AggregateNotFoundException(model.aggregateType(), aggregateId);
        }

        List<Event> events = history.stream()
            .map(serializedEvent -> eventSerializer.deserialize(serializedEvent).payload())
            .toList();
        return EventSourcedAggregate.replay(model, aggregateId, events);
    }

    @SuppressWarnings("unchecked")
    private R invoke(EventSourcedAggregate<A> aggregate, CommandMessage<R, C> commandMessage) {
        var arguments = argumentBinder.toArguments(commandMessage);
        aggregateEventsParameterIndex.ifPresent(index -> arguments[index] = aggregate);
        try {
            return (R) method.invoke(aggregate.instance(), arguments);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to invoke aggregate command handler: " + logMethod(method), e);
        }
    }

    private void append(EventSourcedAggregate<A> aggregate, List<Event> appliedEvents) {
        if (appliedEvents.isEmpty()) {
            return;
        }

        var aggregateId = aggregateIdAfterHandling(aggregate);
        var version = aggregate.loadedVersion();
        for (var event : appliedEvents) {
            version++;
            PendingAggregateEvent.publish(event, new AggregateRevision(model.aggregateType(), aggregateId, version), eventPublisher);
        }
    }

    private String aggregateIdAfterHandling(EventSourcedAggregate<A> aggregate) {
        var aggregateId = model.aggregateId(aggregate.instance()).orElseThrow(() -> new IllegalStateException("""
            Invalid aggregate: @AggregateId is null after applying events. An @EventSourcingHandler must assign it.

                Command handler:
                %s
            """.formatted(logMethod(method))));

        var loadedAggregateId = aggregate.loadedAggregateId();
        if (loadedAggregateId.isPresent() && !loadedAggregateId.get().equals(aggregateId)) {
            throw new IllegalStateException("""
                Invalid aggregate: @AggregateId does not match the loaded stream.

                    Command handler:
                    %s

                    Expected:
                    %s

                    Found:
                    %s
                """.formatted(logMethod(method), loadedAggregateId.get(), aggregateId));
        }
        return aggregateId;
    }

    private Optional<String> targetAggregateId(C command) {
        return targetAggregateIdAccessor.map(accessor -> {
            Object value;
            try {
                value = accessor.invoke(command);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to resolve @TargetAggregateId: " + logMethod(accessor), e);
            }
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("""
                    Invalid command: @TargetAggregateId must resolve to a non-blank value.

                        Command:
                        %s

                        Accessor:
                        %s
                    """.formatted(logClass(commandType), logMethod(accessor)));
            }
            return value.toString();
        });
    }

    private static void validateParameters(Method method, Parameter[] parameters) {
        var commandParameterCount = stream(parameters).filter(parameter -> Command.class.isAssignableFrom(parameter.getType())).count();
        var aggregateEventsParameterCount = stream(parameters).filter(parameter -> parameter.getType() == AggregateEvents.class).count();
        var unsupportedParameters = stream(parameters)
            .filter(parameter -> !Command.class.isAssignableFrom(parameter.getType()))
            .filter(parameter -> parameter.getType() != AggregateEvents.class)
            .filter(parameter -> !CONTEXT_PARAMETER_TYPES.contains(parameter.getType()))
            .filter(parameter -> !(parameter.getType() == String.class && parameter.isAnnotationPresent(MetadataValue.class)))
            .toList();

        if (commandParameterCount != 1 || aggregateEventsParameterCount > 1 || !unsupportedParameters.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: expected exactly one command parameter, at most one AggregateEvents parameter,
                and otherwise only MessageId, Metadata, Principal, Instant, or @MetadataValue String parameters.

                    Method:
                    %s
                """.formatted(logMethod(method)));
        }
    }

    private static Optional<Method> findTargetAggregateIdAccessor(Class<?> commandType) {
        var accessors = concat(stream(commandType.getMethods()), stream(commandType.getDeclaredMethods()))
            .distinct()
            .filter(accessor -> accessor.isAnnotationPresent(TargetAggregateId.class))
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
                Invalid command: at most one accessible, non-static, zero-argument @TargetAggregateId method or record component may be declared.

                    Command:
                    %s

                    Accessors:
                    %s
                """.formatted(logClass(commandType), logMethods(accessors)));
        }
        return Optional.of(accessor);
    }
}
