package app.dodb.smd.api.command;

import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class CommandBusIntegrationTest {

    @Test
    void send() {
        CommandBus commandBus = new CommandBus(
            new MetadataFactory(new SimplePrincipalProvider(), new LocalDatetimeProvider()),
            new CommandHandlerDispatcher(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator())),
            emptyList()
        );

        var command = new IncrementCommand(0);
        var result = commandBus.send(command);

        assertThat(result).isEqualTo(1);
        assertThat(IncrementCommandHandler.handledCommands).containsExactly(command);
    }

    @Test
    void send_withInterceptor() {
        var interceptor = new CommandBusInterceptorForTest();
        CommandBus commandBus = new CommandBus(
            new MetadataFactory(new SimplePrincipalProvider(), new LocalDatetimeProvider()),
            new CommandHandlerDispatcher(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator())),
            List.of(interceptor)
        );

        var command = new IncrementCommand(0);
        commandBus.send(command);

        assertThat(interceptor.getInterceptedCommands()).containsExactly(command);
    }
}