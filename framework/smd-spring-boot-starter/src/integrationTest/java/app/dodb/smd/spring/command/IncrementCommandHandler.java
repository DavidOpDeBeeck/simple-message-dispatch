package app.dodb.smd.spring.command;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;

import java.util.ArrayList;
import java.util.List;

public class IncrementCommandHandler {

    private final List<Command<?>> handledCommands = new ArrayList<>();

    @CommandHandler
    public int handle(IncrementCommand command) {
        handledCommands.add(command);
        return command.value() + 1;
    }

    public List<Command<?>> getHandledCommands() {
        return handledCommands;
    }
}
