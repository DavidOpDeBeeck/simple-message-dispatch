package app.dodb.smd.spring.command;

import app.dodb.smd.api.command.CommandBus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

class CommandBusIntegrationTest {

    @ParameterizedTest(name = "send with {0}")
    @ValueSource(classes = {
        CommandIntegrationTestConfigurationWithDefaults.class,
        CommandIntegrationTestConfigurationWithoutDefaults.class
    })
    void send(Class<?> configClass) {
        try (var context = new SpringApplicationBuilder(configClass).web(NONE).run()) {
            var commandBus = context.getBean(CommandBus.class);
            var incrementCommandHandler = context.getBean(IncrementCommandHandler.class);

            var command = new IncrementCommand(0);

            var result = commandBus.send(command);

            assertThat(result).isEqualTo(1);
            assertThat(incrementCommandHandler.getHandledCommands()).containsExactly(command);
        }
    }
}