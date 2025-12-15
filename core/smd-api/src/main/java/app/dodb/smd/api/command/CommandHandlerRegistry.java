package app.dodb.smd.api.command;

import app.dodb.smd.api.utils.LoggingUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static app.dodb.smd.api.utils.CollectionUtils.combine;
import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

public record CommandHandlerRegistry(Set<AnnotatedCommandHandler<?, ?>> commandHandlers) {

    public static CommandHandlerRegistry empty() {
        return new CommandHandlerRegistry(new HashSet<>());
    }

    public CommandHandlerRegistry {
        requireNonNull(commandHandlers);
        validateUniqueCommandTypeMatchers(commandHandlers);
    }

    @SuppressWarnings("unchecked")
    public <R, C extends Command<R>> CommandHandlerBehaviour<R, C> findBy(CommandMessage<R, C> commandMessage) {
        Object payload = commandMessage.getPayload();
        return commandHandlers.stream()
            .filter(commandHandler -> commandHandler.commandType().isAssignableFrom(payload.getClass()))
            .map(commandHandler -> (CommandHandlerBehaviour<R, C>) commandHandler)
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("No command handler found for " + logClass(payload.getClass())));
    }

    public CommandHandlerRegistry and(CommandHandlerRegistry other) {
        return new CommandHandlerRegistry(combine(commandHandlers, other.commandHandlers));
    }

    private void validateUniqueCommandTypeMatchers(Set<AnnotatedCommandHandler<?, ?>> commandHandlers) {
        for (AnnotatedCommandHandler<?, ?> commandHandler : commandHandlers) {
            var overlappingHandlers = commandHandlers.stream()
                .filter(handler -> commandHandler.commandType().isAssignableFrom(handler.commandType()))
                .toList();

            if (overlappingHandlers.size() > 1) {
                throw new IllegalArgumentException("""
                    Ambiguous command handlers found:
                    
                    Command:
                    %s
                    
                    Methods:
                    %s
                    """.formatted(
                    commandHandler.commandType().getName(),
                    overlappingHandlers.stream()
                        .map(AnnotatedCommandHandler::method)
                        .map(LoggingUtils::logMethod)
                        .collect(Collectors.joining("\n"))));
            }
        }
    }
}
