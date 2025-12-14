package app.dodb.smd.api.command;

import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBusIntegrationTest {

    @Test
    void send() {
        var commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator()))
            .create();

        var command = new IncrementCommand(0);
        var result = commandBus.send(command);

        assertThat(result).isEqualTo(1);
        assertThat(IncrementCommandHandler.handledCommands).containsExactly(command);
    }

    @Test
    void send_withInterceptor() {
        var interceptor = new CommandBusInterceptorForTest();
        var commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator()))
            .interceptors(interceptor)
            .create();

        var command = new IncrementCommand(0);
        commandBus.send(command);

        assertThat(interceptor.getInterceptedCommands()).containsExactly(command);
    }
}