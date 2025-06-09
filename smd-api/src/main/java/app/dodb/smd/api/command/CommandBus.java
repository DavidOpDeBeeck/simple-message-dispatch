package app.dodb.smd.api.command;

import app.dodb.smd.api.metadata.MetadataFactory;

import static java.util.Objects.requireNonNull;

public class CommandBus implements CommandGateway {

    private final MetadataFactory metadataFactory;
    private final CommandHandlerDispatcher dispatcher;

    public CommandBus(MetadataFactory metadataFactory,
                      CommandHandlerDispatcher dispatcher) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.dispatcher = requireNonNull(dispatcher);
    }

    @Override
    public <R, C extends Command<R>> R send(C command) {
        return send(CommandMessage.from(command, metadataFactory.create()));
    }

    @Override
    public <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage) {
        return dispatcher.dispatch(commandMessage);
    }
}
