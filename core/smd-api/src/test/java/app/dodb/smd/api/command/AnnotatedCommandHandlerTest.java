package app.dodb.smd.api.command;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
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
            .hasMessageStartingWith("Invalid handler: method must include a parameter of type %s.".formatted(logClass(Command.class)));
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
    void handle_withCommandAndMultipleMetadataValueParameters() {
        var registry = AnnotatedCommandHandler.from(new CommandHandlerWithCommandAndMultipleMetadataValueParameters());

        assertThat(registry.commandHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithoutMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: metadata value parameter must be annotated with @MetadataValue.");
    }

    @Test
    void handle_withIncorrectMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithIncorrectMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: only parameters of type String can be annotated with @MetadataValue.");
    }

    @Test
    void handle_withDuplicateMessageIdParameters() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithDuplicateMessageIdParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(MessageId.class)));
    }

    @Test
    void handle_withDuplicateMetadataParameters() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithDuplicateMetadataParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(Metadata.class)));
    }

    @Test
    void handle_withDuplicatePrincipalParameters() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithDuplicatePrincipalParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(app.dodb.smd.api.metadata.principal.Principal.class)));
    }

    @Test
    void handle_withDuplicateInstantParameters() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithDuplicateInstantParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(Instant.class)));
    }

    @Test
    void handle_withoutParameters() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithoutParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must have at least one parameter.");
    }

    @Test
    void handle_withMultipleCommandTypes() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new CommandHandlerWithMultipleCommandTypes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(Command.class)));
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

    @Test
    void handle_withNonPublicAnnotatedMethod() {
        assertThatThrownBy(() -> AnnotatedCommandHandler.from(new NonPublicAnnotatedCommandHandler()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid command handler: method must be public.");
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

    public static class CommandHandlerWithCommandAndMultipleMetadataValueParameters {

        @CommandHandler
        public String handle(CommandForTest command, @MetadataValue("value1") String value1, @MetadataValue("value2") String value2) {
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

    public static class CommandHandlerWithDuplicateMessageIdParameters {

        @CommandHandler
        public String handle(CommandForTest command, MessageId messageId1, MessageId messageId2) {
            return "";
        }
    }

    public static class CommandHandlerWithDuplicateMetadataParameters {

        @CommandHandler
        public String handle(CommandForTest command, Metadata metadata1, Metadata metadata2) {
            return "";
        }
    }

    public static class CommandHandlerWithDuplicatePrincipalParameters {

        @CommandHandler
        public String handle(CommandForTest command, SimplePrincipal principal1, SimplePrincipal principal2) {
            return "";
        }
    }

    public static class CommandHandlerWithDuplicateInstantParameters {

        @CommandHandler
        public String handle(CommandForTest command, Instant instant1, Instant instant2) {
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

    public static class NonPublicAnnotatedCommandHandler {

        @CommandHandler
        String handle(CommandForTest command) {
            return "";
        }
    }
}
