package app.dodb.smd.api.command;

import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.PrincipalProviderImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBusIntegrationTest {

    @Test
    void send() {
        CommandBus commandBus = new CommandBus(
            new MetadataFactory(new PrincipalProviderImpl(), new LocalDatetimeProvider()),
            new CommandHandlerDispatcher(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator()))
        );

        var command = new IncrementCommand(0);
        var result = commandBus.send(command);

        assertThat(result).isEqualTo(1);
        assertThat(IncrementCommandHandler.handledCommands).containsExactly(command);
    }
}