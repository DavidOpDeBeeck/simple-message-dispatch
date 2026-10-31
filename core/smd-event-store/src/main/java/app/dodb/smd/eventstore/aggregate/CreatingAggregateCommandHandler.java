package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.utils.handlerstrategy.AtLeastOneParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.AtMostOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.ExactlyOneAssignableParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy;
import app.dodb.smd.api.utils.handlerstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.PublicStrategy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.TypeUtils.resolveGenericType;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Objects.requireNonNull;

record CreatingAggregateCommandHandler<R, C extends Command<R>, A>(AggregateRepository<A> repository,
                                                                   Constructor<A> constructor,
                                                                   Class<C> commandType,
                                                                   AggregateCommandArguments<R, C> arguments)
    implements AnnotatedAggregateCommandHandler<R, C> {

    private static final List<HandlerValidationStrategy> HANDLER_VALIDATION_STRATEGIES = List.of(
        new PublicStrategy(),
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Command.class),
        new AtMostOneAssignableParameterStrategy(AggregateEvents.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy()
    );

    CreatingAggregateCommandHandler {
        requireNonNull(repository);
        requireNonNull(constructor);
        requireNonNull(commandType);
        requireNonNull(arguments);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <A> CreatingAggregateCommandHandler<?, ?, A> of(AggregateRepository<A> repository, Constructor<A> constructor, ObjectCreator objectCreator) {
        validateHandler(constructor, HANDLER_VALIDATION_STRATEGIES);
        if (!constructor.trySetAccessible()) {
            throw new IllegalArgumentException("Invalid aggregate command handler: the handler is not accessible: " + logMethod(constructor));
        }

        Class commandType = AggregateCommandArguments.commandType(constructor);
        if (TargetAggregateIdAccessor.find(commandType).isPresent()) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: a creating command must not declare @TargetAggregateId. The aggregate assigns its own @AggregateId.
                
                    Constructor:
                    %s
                
                    Command:
                    %s
                """.formatted(logMethod(constructor), logClass(commandType)));
        }

        var aggregateIdType = repository.model().aggregateIdType();
        var commandReturnType = resolveGenericType(commandType, Command.class);
        if (!haveSameBounds(aggregateIdType, commandReturnType)) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: a creating command must return the @AggregateId.
                
                    Constructor:
                    %s
                
                    Expected:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(constructor), logClass(aggregateIdType), logClass(commandReturnType)));
        }

        return new CreatingAggregateCommandHandler<>(repository, constructor, commandType, AggregateCommandArguments.from(constructor, commandType, objectCreator));
    }

    @Override
    @SuppressWarnings("unchecked")
    public R handle(CommandMessage<R, C> commandMessage) {
        var aggregate = repository.<Event>create(events -> newInstance(arguments.toArguments(commandMessage, events)));
        return (R) repository.model().aggregateId(aggregate).orElseThrow();
    }

    @Override
    public String toString() {
        return "@AggregateCommandHandler " + logMethod(constructor);
    }

    private A newInstance(Object[] arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke aggregate command handler: " + logMethod(constructor), e);
        }
    }
}
