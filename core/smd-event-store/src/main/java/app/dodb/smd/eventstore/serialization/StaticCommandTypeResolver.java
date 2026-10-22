package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.command.Command;

import java.util.Map;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

public class StaticCommandTypeResolver implements CommandTypeResolver {

    private final Map<String, Class<? extends Command<?>>> commandClassByCommandType;
    private final Map<Class<? extends Command<?>>, String> commandTypeByCommandClass;

    public StaticCommandTypeResolver(Map<String, Class<? extends Command<?>>> commandClassByCommandType) {
        this.commandClassByCommandType = requireNonNull(commandClassByCommandType);
        this.commandTypeByCommandClass = commandClassByCommandType.entrySet().stream()
            .collect(toMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    @Override
    public String commandTypeFor(Command<?> command) throws CommandTypeResolutionException {
        if (commandTypeByCommandClass.containsKey(command.getClass())) {
            return commandTypeByCommandClass.get(command.getClass());
        }
        throw new CommandTypeResolutionException("Command class not found: " + command.getClass());
    }

    @Override
    public Class<? extends Command<?>> commandClassFor(String commandType) throws CommandTypeResolutionException {
        if (commandClassByCommandType.containsKey(commandType)) {
            return commandClassByCommandType.get(commandType);
        }
        throw new CommandTypeResolutionException("Command type not found: " + commandType);
    }
}
