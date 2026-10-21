package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.eventstore.deadline.SerializedDeadline;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static app.dodb.smd.eventstore.serialization.JacksonDeadlineSerializer.DeadlineSerializationException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonDeadlineSerializerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-10-22T09:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-10-24T09:00:00Z");
    private static final SimplePrincipal PRINCIPAL = new SimplePrincipal(UUID.fromString("d9aa7511-e40c-4ef0-bc50-abef81f7d00f"));
    private static final MessageId PARENT_MESSAGE_ID = new MessageId(UUID.fromString("14d68b69-d3f7-4d61-88de-175938716af0"));

    private final JsonMapper objectMapper = JsonMapper.builder().addModule(new SMDJacksonModule()).build();

    @Test
    void serialize_withStaticTypeResolver_usesMappedCommandTypeAndMessageIdAsDeadlineId() {
        var serializer = new JacksonDeadlineSerializer(objectMapper, new StaticCommandTypeResolver(Map.of(
            "ticket.escalate.v1", EscalateTicketCommand.class
        )));
        var commandMessage = commandMessage();

        var serialized = serializer.serialize(commandMessage, DUE_AT);

        assertThat(serialized.deadlineId()).isEqualTo(new DeadlineId(commandMessage.messageId().value()));
        assertThat(serialized.commandType()).isEqualTo("ticket.escalate.v1");
        assertThat(serialized.dueAt()).isEqualTo(DUE_AT);
        assertThat(new String(serialized.serializedPayload(), UTF_8)).isEqualTo("{\"ticketId\":\"ticket-1\"}");
    }

    @Test
    void deserialize_serializedDeadline_restoresMessageIdPayloadAndMetadata() {
        var serializer = new JacksonDeadlineSerializer(objectMapper);
        var commandMessage = commandMessage();

        var deserialized = serializer.deserialize(serializer.serialize(commandMessage, DUE_AT));

        assertThat(deserialized).isEqualTo(commandMessage);
    }

    @Test
    void serialize_withUnmappedCommandClass_throwsWithDeadlineId() {
        var serializer = new JacksonDeadlineSerializer(objectMapper, new StaticCommandTypeResolver(Map.of()));
        var commandMessage = commandMessage();

        assertThatThrownBy(() -> serializer.serialize(commandMessage, DUE_AT))
            .isInstanceOf(DeadlineSerializationException.class)
            .hasMessage("Failed to resolve command type for deadline: deadlineId=" + commandMessage.messageId().value())
            .hasCauseInstanceOf(CommandTypeResolutionException.class);
    }

    @Test
    void deserialize_withUnknownCommandType_throwsWithDeadlineIdAndCommandType() {
        var serializer = new JacksonDeadlineSerializer(objectMapper);
        var deadlineId = DeadlineId.generate();
        var deadline = new SerializedDeadline(deadlineId, "app.dodb.Missing", new byte[0], new byte[0], DUE_AT);

        assertThatThrownBy(() -> serializer.deserialize(deadline))
            .isInstanceOf(DeadlineSerializationException.class)
            .hasMessage("Failed to resolve command type: deadlineId=" + deadlineId.value() + ", commandType=app.dodb.Missing")
            .hasCauseInstanceOf(CommandTypeResolutionException.class);
    }

    private static CommandMessage<Void, EscalateTicketCommand> commandMessage() {
        var metadata = new Metadata(PRINCIPAL, TIMESTAMP, PARENT_MESSAGE_ID, Map.of("tenantId", "acme"));
        return CommandMessage.from(new EscalateTicketCommand("ticket-1"), metadata);
    }

    public record EscalateTicketCommand(String ticketId) implements Command<Void> {
    }
}
