package app.dodb.smd.api.command;

public interface CommandGateway {

    <R, C extends Command<R>> R send(C command);

    <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage);
}
