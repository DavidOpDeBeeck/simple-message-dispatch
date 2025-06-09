package app.dodb.smd.api.command;

import static java.util.Objects.requireNonNull;

public class CommandHandlerDispatcher {

    private final CommandHandlerRegistry commandHandlerRegistry;

    public CommandHandlerDispatcher(CommandHandlerLocator commandHandlerLocator) {
        this.commandHandlerRegistry = requireNonNull(commandHandlerLocator).locate();
    }

    public <R, C extends Command<R>> R dispatch(CommandMessage<R, C> commandMessage) {
        return commandHandlerRegistry.findBy(commandMessage).handle(commandMessage);
    }
}
