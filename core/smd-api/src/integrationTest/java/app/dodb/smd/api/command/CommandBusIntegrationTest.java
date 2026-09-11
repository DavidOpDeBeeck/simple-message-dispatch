package app.dodb.smd.api.command;

import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandBusIntegrationTest {

    @BeforeEach
    void setUp() {
        IncrementCommandHandler.handledCommands.clear();
    }

    @Test
    void send_withDiscoveredHandler_returnsResult() {
        // Given
        var commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator()))
            .create();

        var command = new IncrementCommand(0);

        // When
        var result = commandBus.send(command);

        // Then
        assertThat(result).isEqualTo(1);
        assertThat(IncrementCommandHandler.handledCommands).containsExactly(command);
    }

    @Test
    void send_withInterceptor_interceptsCommand() {
        // Given
        var interceptor = new CommandBusInterceptorForTest();
        var commandBus = CommandBusSpec.withDefaults()
            .commandHandlers(new PackageBasedCommandHandlerLocator(List.of("app.dodb.smd.api.command"), new ConstructorBasedObjectCreator()))
            .interceptors(interceptor)
            .create();

        var command = new IncrementCommand(0);

        // When
        commandBus.send(command);

        // Then
        assertThat(interceptor.getInterceptedCommands()).containsExactly(command);
    }
}
