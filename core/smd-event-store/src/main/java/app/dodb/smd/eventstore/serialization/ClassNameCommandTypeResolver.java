package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.command.Command;

public class ClassNameCommandTypeResolver implements CommandTypeResolver {

    @Override
    public String commandTypeFor(Command<?> command) {
        return command.getClass().getName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Command<?>> commandClassFor(String commandType) throws CommandTypeResolutionException {
        try {
            var commandClass = Class.forName(commandType);
            if (!Command.class.isAssignableFrom(commandClass)) {
                throw new CommandTypeResolutionException("Command type does not implement Command: " + commandType);
            }
            return (Class<? extends Command<?>>) commandClass;
        } catch (ClassNotFoundException e) {
            throw new CommandTypeResolutionException("Command type not found: " + commandType, e);
        }
    }
}
