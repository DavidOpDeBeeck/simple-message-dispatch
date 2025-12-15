package app.dodb.smd.api.command;

import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusInterceptorChain;

import java.util.ArrayList;
import java.util.List;

public class CommandBusInterceptorForTest implements CommandBusInterceptor {

    private final List<Command<?>> interceptedCommands = new ArrayList<>();

    @Override
    public <R, C extends Command<R>> R intercept(CommandMessage<R, C> commandMessage, CommandBusInterceptorChain<R, C> chain) {
        interceptedCommands.add(commandMessage.payload());
        return chain.proceed(commandMessage);
    }

    public List<Command<?>> getInterceptedCommands() {
        return interceptedCommands;
    }
}
