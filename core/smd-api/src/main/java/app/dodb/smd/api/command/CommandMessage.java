package app.dodb.smd.api.command;

import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;

import static java.util.Objects.requireNonNull;

public record CommandMessage<R, C extends Command<R>>(MessageId messageId, C payload, Metadata metadata) implements Message<C, CommandMessage<R, C>> {

    public static <R, C extends Command<R>> CommandMessage<R, C> from(C payload, Metadata metadata) {
        return new CommandMessage<>(MessageId.generate(), payload, metadata);
    }

    public CommandMessage {
        requireNonNull(messageId);
        requireNonNull(payload);
        requireNonNull(metadata);
    }

    @Override
    public CommandMessage<R, C> withMetadata(Metadata newMetadata) {
        return new CommandMessage<>(messageId, payload, newMetadata);
    }

    @Override
    public CommandMessage<R, C> andMetadata(Metadata newMetadata) {
        return new CommandMessage<>(messageId, payload, metadata.and(newMetadata));
    }
}
