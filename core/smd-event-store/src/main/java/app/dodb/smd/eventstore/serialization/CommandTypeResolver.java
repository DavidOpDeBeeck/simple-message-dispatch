package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.command.Command;

public interface CommandTypeResolver {

    String commandTypeFor(Command<?> command) throws CommandTypeResolutionException;

    Class<? extends Command<?>> commandClassFor(String commandType) throws CommandTypeResolutionException;
}
