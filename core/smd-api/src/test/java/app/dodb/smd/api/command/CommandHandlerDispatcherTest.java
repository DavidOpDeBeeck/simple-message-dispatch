package app.dodb.smd.api.command;

import org.junit.jupiter.api.Test;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandHandlerDispatcherTest {

    @Test
    void dispatch() {
        var dispatcher = new CommandHandlerDispatcher(() -> AnnotatedCommandHandler.from(new CommandHandlerForTest()));

        var result = dispatcher.dispatch(CommandMessage.from(new CommandForTest("Hello world"), METADATA));

        assertThat(result).isEqualTo("Hello world");
    }

    @Test
    void dispatch_whenCommandHandlerThrowsException_thenRethrow() {
        var dispatcher = new CommandHandlerDispatcher(() -> AnnotatedCommandHandler.from(new CommandHandlerThatThrowsException()));
        var commandMessage = CommandMessage.from(new CommandForTest("Hello world"), METADATA);

        assertThatThrownBy(() -> dispatcher.dispatch(commandMessage))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("this is an exception");
    }

    public record CommandForTest(String value) implements Command<String> {
    }

    public static class CommandHandlerForTest {

        @CommandHandler
        public String handle(CommandForTest command) {
            return command.value();
        }
    }

    public static class CommandHandlerThatThrowsException {

        @CommandHandler
        public String handle(CommandForTest command) {
            throw new RuntimeException("this is an exception");
        }
    }
}