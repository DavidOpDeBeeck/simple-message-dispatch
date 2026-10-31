package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandHandlerBehaviour;
import app.dodb.smd.api.command.CommandHandlerRegistry;
import app.dodb.smd.api.framework.ObjectCreator;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static app.dodb.smd.api.utils.LoggingUtils.logMethods;
import static java.util.Arrays.stream;

public sealed interface AnnotatedAggregateCommandHandler<R, C extends Command<R>> extends CommandHandlerBehaviour<R, C>
    permits CreatingAggregateCommandHandler, UpdatingAggregateCommandHandler {

    @SuppressWarnings("unchecked")
    static <A> CommandHandlerRegistry from(AggregateRepository<A> repository, ObjectCreator objectCreator) {
        var aggregateClass = repository.model().aggregateClass();
        var plainCommandHandlers = stream(aggregateClass.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(CommandHandler.class))
            .toList();
        if (!plainCommandHandlers.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid aggregate command handler: aggregates handle commands with @AggregateCommandHandler instead of @CommandHandler.
                
                    Methods:
                    %s
                """.formatted(logMethods(plainCommandHandlers)));
        }

        var creatingHandlers = stream(aggregateClass.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(AggregateCommandHandler.class))
            .map(constructor -> CreatingAggregateCommandHandler.<A>of(repository, (Constructor<A>) constructor, objectCreator))
            .toList();
        var updatingHandlers = stream(aggregateClass.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(AggregateCommandHandler.class))
            .map(method -> UpdatingAggregateCommandHandler.<A>of(repository, method, objectCreator))
            .toList();
        if (creatingHandlers.isEmpty()) {
            throw new IllegalArgumentException("""
                Invalid aggregate: an @AggregateCommandHandler constructor must create the aggregate that its @AggregateCommandHandler methods update.
                
                    Aggregate:
                    %s
                """.formatted(logClass(aggregateClass)));
        }

        Set<CommandHandlerBehaviour<?, ?>> handlers = new HashSet<>(creatingHandlers);
        handlers.addAll(updatingHandlers);
        return new CommandHandlerRegistry(handlers);
    }
}
