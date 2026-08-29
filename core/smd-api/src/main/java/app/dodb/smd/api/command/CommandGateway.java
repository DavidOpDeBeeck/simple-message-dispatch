package app.dodb.smd.api.command;

import app.dodb.smd.api.metadata.Metadata;

public interface CommandGateway {

    <R, C extends Command<R>> R send(C command);

    <R, C extends Command<R>> R send(C command, Metadata metadata);

    <R, C extends Command<R>> R send(CommandMessage<R, C> commandMessage);
}
