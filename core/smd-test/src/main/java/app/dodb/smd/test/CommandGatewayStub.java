package app.dodb.smd.test;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandMessage;

import java.util.HashMap;
import java.util.Map;

public class CommandGatewayStub implements CommandGateway {

    private final Map<Command<?>, Object> responseByCommand = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <R, C extends Command<R>> R send(C command) {
        return (R) responseByCommand.get(command);
    }

    @Override
    public <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage) {
        return send(commandMessage.getPayload());
    }

    public <R, C extends Command<R>> void stubCommand(C command, R response) {
        responseByCommand.put(command, response);
    }

    public void reset() {
        responseByCommand.clear();
    }
}
