package app.dodb.smd.api.command.bus;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;

public interface CommandBusInterceptor {

    <R, C extends Command<R>> R intercept(CommandMessage<R, C> commandMessage, CommandBusInterceptorChain<R, C> chain);
}
