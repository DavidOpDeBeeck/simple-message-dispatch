package app.dodb.smd.api.command;

import java.util.ArrayList;
import java.util.List;

public class IncrementCommandHandler {

    static List<Command<?>> handledCommands = new ArrayList<>();

    @CommandHandler
    public Integer handle(IncrementCommand command) {
        handledCommands.add(command);
        return command.value() + 1;
    }
}
