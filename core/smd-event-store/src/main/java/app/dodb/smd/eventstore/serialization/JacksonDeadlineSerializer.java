package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.deadline.SerializedDeadline;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public class JacksonDeadlineSerializer implements DeadlineSerializer {

    private final ObjectMapper objectMapper;
    private final CommandTypeResolver commandTypeResolver;

    public JacksonDeadlineSerializer(ObjectMapper objectMapper) {
        this(objectMapper, new ClassNameCommandTypeResolver());
    }

    public JacksonDeadlineSerializer(ObjectMapper objectMapper, CommandTypeResolver commandTypeResolver) {
        this.objectMapper = requireNonNull(objectMapper);
        this.commandTypeResolver = requireNonNull(commandTypeResolver);
    }

    @Override
    public <R, C extends Command<R>> SerializedDeadline serialize(CommandMessage<R, C> commandMessage, Instant dueAt) {
        var deadlineId = new DeadlineId(commandMessage.messageId().value());
        try {
            return new SerializedDeadline(
                deadlineId,
                commandTypeResolver.commandTypeFor(commandMessage.payload()),
                objectMapper.writeValueAsBytes(commandMessage.payload()),
                objectMapper.writeValueAsBytes(commandMessage.metadata()),
                dueAt
            );
        } catch (CommandTypeResolutionException e) {
            throw new DeadlineSerializationException("Failed to resolve command type for deadline: deadlineId=" + deadlineId.value(), e);
        } catch (JacksonException e) {
            throw new DeadlineSerializationException("Failed to serialize deadline: deadlineId=" + deadlineId.value(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, C extends Command<R>> CommandMessage<R, C> deserialize(SerializedDeadline deadline) {
        try {
            var commandClass = commandTypeResolver.commandClassFor(deadline.commandType());
            var payload = (C) objectMapper.readValue(deadline.serializedPayload(), commandClass);
            var metadata = objectMapper.readValue(deadline.serializedMetadata(), Metadata.class);
            return new CommandMessage<>(new MessageId(deadline.deadlineId().value()), payload, metadata);
        } catch (CommandTypeResolutionException e) {
            throw new DeadlineSerializationException("Failed to resolve command type: deadlineId=" + deadline.deadlineId().value()
                + ", commandType=" + deadline.commandType(), e);
        } catch (JacksonException e) {
            throw new DeadlineSerializationException("Failed to deserialize deadline: deadlineId=" + deadline.deadlineId().value(), e);
        }
    }

    public static class DeadlineSerializationException extends RuntimeException {
        public DeadlineSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
