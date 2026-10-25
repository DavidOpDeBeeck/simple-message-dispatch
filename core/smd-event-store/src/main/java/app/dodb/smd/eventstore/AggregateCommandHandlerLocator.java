package app.dodb.smd.eventstore;

import app.dodb.smd.api.command.CommandHandlerBehaviour;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.CommandHandlerRegistry;
import app.dodb.smd.api.event.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

final class AggregateCommandHandlerLocator implements CommandHandlerLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AggregateCommandHandlerLocator.class);

    private final List<Class<?>> aggregateClasses;
    private final EventStoreConfig config;
    private final EventPublisher eventPublisher;

    AggregateCommandHandlerLocator(List<Class<?>> aggregateClasses, EventStoreConfig config, EventPublisher eventPublisher) {
        this.aggregateClasses = List.copyOf(aggregateClasses);
        this.config = requireNonNull(config);
        this.eventPublisher = requireNonNull(eventPublisher);
    }

    @Override
    public CommandHandlerRegistry locate() {
        var aggregateClassesByType = new HashMap<String, Class<?>>();
        Set<CommandHandlerBehaviour<?, ?>> handlers = new HashSet<>();

        for (var aggregateClass : aggregateClasses) {
            var model = AggregateModel.inspect(aggregateClass);
            var duplicate = aggregateClassesByType.putIfAbsent(model.aggregateType(), aggregateClass);
            if (duplicate != null && duplicate != aggregateClass) {
                throw new IllegalArgumentException("""
                    Duplicate aggregate type name: %s

                        Aggregates:
                        %s, %s
                    """.formatted(model.aggregateType(), logClass(duplicate), logClass(aggregateClass)));
            }
            handlers.addAll(commandHandlers(model));
        }

        LOGGER.info("Located {} aggregate command handlers on aggregates {}", handlers.size(), aggregateClassesByType.keySet());
        return new CommandHandlerRegistry(handlers);
    }

    private <A> List<CommandHandlerBehaviour<?, ?>> commandHandlers(AggregateModel<A> model) {
        var creationHandlers = model.creationHandlers().stream()
            .map(constructor -> AggregateCommandHandler.creating(model, constructor, config, eventPublisher));
        var commandHandlers = model.commandHandlers().stream()
            .map(method -> AggregateCommandHandler.handling(model, method, config, eventPublisher));
        return Stream.<CommandHandlerBehaviour<?, ?>>concat(creationHandlers, commandHandlers).toList();
    }
}
