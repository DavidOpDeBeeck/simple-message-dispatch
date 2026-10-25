package app.dodb.smd.api.command;

import java.lang.reflect.Executable;

public interface CommandHandlerBehaviour<R, C extends Command<R>> {

    Class<C> commandType();

    Executable executable();

    R handle(CommandMessage<R, C> commandMessage);
}
