package app.dodb.smd.api.command;

public interface CommandHandlerBehaviour<R, C extends Command<R>> {

    Class<C> commandType();

    R handle(CommandMessage<R, C> commandMessage);
}
