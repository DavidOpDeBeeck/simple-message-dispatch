package app.dodb.smd.api.command.bus;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandlerDispatcher;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.metadata.MetadataFactory;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class CommandBus implements CommandGateway {

    private final MetadataFactory metadataFactory;
    private final CommandHandlerDispatcher dispatcher;
    private final List<CommandBusInterceptor> interceptors;

    CommandBus(MetadataFactory metadataFactory,
               List<CommandBusInterceptor> interceptors,
               CommandHandlerDispatcher dispatcher) {
        this.metadataFactory = requireNonNull(metadataFactory);
        this.dispatcher = requireNonNull(dispatcher);
        this.interceptors = requireNonNull(interceptors);
    }

    @Override
    public <R, C extends Command<R>> R send(C command) {
        var chain = CommandBusInterceptorChain.<R, C>create(dispatcher::dispatch, interceptors);
        return metadataFactory.createScope().run(metadata -> {
            return chain.proceed(CommandMessage.from(command, metadata));
        });
    }

    @Override
    public <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage) {
        var chain = CommandBusInterceptorChain.<R, C>create(dispatcher::dispatch, interceptors);
        return metadataFactory.createScope(commandMessage.getMetadata()).run(metadata -> {
            return chain.proceed(commandMessage.withMetadata(metadata));
        });
    }
}
