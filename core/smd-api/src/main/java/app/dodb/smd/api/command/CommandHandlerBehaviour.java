package app.dodb.smd.api.command;

import java.lang.reflect.Method;

public interface CommandHandlerBehaviour<R, C extends Command<R>> {

    Class<C> commandType();

    Method method();

    R handle(CommandMessage<R, C> commandMessage);
}
