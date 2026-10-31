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
import app.dodb.smd.api.utils.handlerstrategy.MessageReturnTypeStrategy;
import app.dodb.smd.api.utils.handlerstrategy.MetadataValueParameterStrategy;
import app.dodb.smd.api.utils.handlerstrategy.NonStaticStrategy;
import app.dodb.smd.api.utils.handlerstrategy.PublicStrategy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethod;
import static app.dodb.smd.api.utils.TypeUtils.haveSameBounds;
import static app.dodb.smd.api.utils.handlerstrategy.HandlerValidationStrategy.validateHandler;
import static java.util.Objects.requireNonNull;

record UpdatingAggregateCommandHandler<R, C extends Command<R>, A>(AggregateRepository<A> repository,
                                                                   Method method,
                                                                   Class<C> commandType,
                                                                   TargetAggregateIdAccessor targetAggregateId,
                                                                   AggregateCommandArguments<R, C> arguments)
    implements AnnotatedAggregateCommandHandler<R, C> {

    private static final List<HandlerValidationStrategy> HANDLER_VALIDATION_STRATEGIES = List.of(
        new PublicStrategy(),
        new NonStaticStrategy(),
        new AtLeastOneParameterStrategy(),
        new ExactlyOneAssignableParameterStrategy(Command.class),
        new AtMostOneAssignableParameterStrategy(AggregateEvents.class),
        new AtMostOneAssignableParameterStrategy(MessageId.class),
        new AtMostOneAssignableParameterStrategy(Metadata.class),
        new AtMostOneAssignableParameterStrategy(Principal.class),
        new AtMostOneAssignableParameterStrategy(Instant.class),
        new MetadataValueParameterStrategy(),
        new MessageReturnTypeStrategy(Command.class)
    );

    UpdatingAggregateCommandHandler {
        requireNonNull(repository);
        requireNonNull(method);
        requireNonNull(commandType);
        requireNonNull(targetAggregateId);
        requireNonNull(arguments);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <A> UpdatingAggregateCommandHandler<?, ?, A> of(AggregateRepository<A> repository, Method method, ObjectCreator objectCreator) {
        validateHandler(method, HANDLER_VALIDATION_STRATEGIES);
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("Invalid aggregate command handler: the handler is not accessible: " + logMethod(method));
        }

        Class commandType = AggregateCommandArguments.commandType(method);
        var targetAggregateId = TargetAggregateIdAccessor.find(commandType).orElseThrow(() -> new IllegalArgumentException("""
            Invalid aggregate command handler: the command must declare @TargetAggregateId to select the aggregate.
            
                Method:
                %s
            
                Command:
                %s
            """.formatted(logMethod(method), logClass(commandType))));

        var aggregateIdType = repository.model().aggregateIdType();
        var targetAggregateIdType = targetAggregateId.accessor().getGenericReturnType();
        if (!haveSameBounds(aggregateIdType, targetAggregateIdType)) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: the @TargetAggregateId must have the type of the @AggregateId.
                
                    Method:
                    %s
                
                    Expected:
                    %s
                
                    Found:
                    %s
                """.formatted(logMethod(method), logClass(aggregateIdType), logClass(targetAggregateIdType)));
        }

        return new UpdatingAggregateCommandHandler<>(repository, method, commandType, targetAggregateId, AggregateCommandArguments.from(method, commandType, objectCreator));
    }

    @Override
    public R handle(CommandMessage<R, C> commandMessage) {
        var aggregateId = targetAggregateId.resolve(commandMessage.payload());
        return repository.<R, Event>update(aggregateId, (aggregate, events) -> invoke(aggregate, arguments.toArguments(commandMessage, events)));
    }

    @Override
    public String toString() {
        return "@AggregateCommandHandler " + logMethod(method);
    }

    @SuppressWarnings("unchecked")
    private R invoke(A aggregate, Object[] arguments) {
        try {
            return (R) method.invoke(aggregate, arguments);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to invoke aggregate command handler: " + logMethod(method), e);
        }
    }
}
