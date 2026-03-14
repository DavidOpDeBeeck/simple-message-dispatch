package app.dodb.smd.api.command;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotatedCommandHandlerTest {

    @Test
    void handle_withCommandParameter() {
        var registry = AnnotatedCommandHandler.from(new CommandHandlerWithCommandParameter());

        assertThat(registry.commandHandlers()).hasSize(1);
    }

    @Test
    void handle_withGenericCommandParameter() {
        var registry = AnnotatedCommandHandler.from(new CommandHandlerWithGenericCommandParameter());

        assertThat(registry.commandHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutCommandParameter() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithoutCommandParameter()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: method must include a parameter of type Command.");
    }

    @Test
    void handle_withCommandAndMetadataParameter() {
        var registry = AnnotatedCommandHandler.from(new CommandHandlerWithCommandAndMetadataParameter());

        assertThat(registry.commandHandlers()).hasSize(1);
    }

    @Test
    void handle_withCommandAndMetadataValueParameter() {
        var registry = AnnotatedCommandHandler.from(new CommandHandlerWithCommandAndMetadataValueParameter());

        assertThat(registry.commandHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithoutMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: metadata value parameter must be annotated with @MetadataValue.");
    }

    @Test
    void handle_withIncorrectMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithIncorrectMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: only parameters of type String can be annotated with @MetadataValue.");
    }

    @Test
    void handle_withoutParameters() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithoutParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: method must have at least one parameter.");
    }

    @Test
    void handle_withMultipleCommandTypes() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithMultipleCommandTypes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: method must only include one Command as a parameter.");
    }

    @Test
    void handle_withIncorrectReturnType() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithIncorrectReturnType()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: return type mismatch.");
    }

    @Test
    void handle_withVoidReturnType() {
        var registry = AnnotatedCommandHandler.from(new CommandHandlerWithVoidReturnType());

        assertThat(registry.commandHandlers()).hasSize(1);
    }

    @Test
    void handle_withMultipleHandlersForSameCommand() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new MultipleCommandHandlersForSameCommand()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Ambiguous command handlers found");
    }

    public record CommandForTest() implements Command<String> {
    }

    public record GenericCommandForTest<T>() implements Command<T> {
    }

    public record AnotherCommandForTest() implements Command<Integer> {
    }

    public record YetAnotherCommandForTest() implements Command<Void> {
    }

    public static class CommandHandlerWithCommandParameter {

        @CommandHandler
        public String handle(CommandForTest command) {
            return "";
        }
    }

    public static class CommandHandlerWithGenericCommandParameter {

        @CommandHandler
        public <R> R handle(Command<R> command) {
            return null;
        }
    }

    public static class CommandHandlerWithCommandAndMetadataParameter {

        @CommandHandler
        public String handle(CommandForTest command, Metadata metadata, MessageId messageId, SimplePrincipal principal, Instant timestamp) {
            return "";
        }
    }

    public static class CommandHandlerWithCommandAndMetadataValueParameter {

        @CommandHandler
        public String handle(CommandForTest command, @MetadataValue("value") String value) {
            return "";
        }
    }

    public static class CommandHandlerWithoutMetadataValueAnnotation {

        @CommandHandler
        public String handle(CommandForTest command, String value) {
            return "";
        }
    }

    public static class CommandHandlerWithIncorrectMetadataValueAnnotation {

        @CommandHandler
        public String handle(CommandForTest command, @MetadataValue("property") Metadata metadata) {
            return "";
        }
    }

    public static class MultipleCommandHandlersForSameCommand {

        @CommandHandler
        public String handle(CommandForTest command) {
            return "";
        }

        @CommandHandler
        public String handle2(CommandForTest command) {
            return "";
        }
    }

    public static class CommandHandlerWithoutCommandParameter {

        @CommandHandler
        public String handle(Metadata metadata) {
            return "";
        }
    }

    public static class CommandHandlerWithoutParameters {

        @CommandHandler
        public String handle() {
            return "";
        }
    }

    public static class CommandHandlerWithMultipleCommandTypes {

        @CommandHandler
        public Integer handle(CommandForTest command, AnotherCommandForTest anotherCommand) {
            return 0;
        }
    }

    public static class CommandHandlerWithIncorrectReturnType {

        @CommandHandler
        public Integer handle(CommandForTest command) {
            return 0;
        }
    }

    public static class CommandHandlerWithVoidReturnType {

        @CommandHandler
        public void handle(YetAnotherCommandForTest command) {
        }
    }
}