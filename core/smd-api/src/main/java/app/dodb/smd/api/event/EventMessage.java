package app.dodb.smd.api.event;

import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record EventMessage<E extends Event>(MessageId messageId, Optional<String> subjectId, E payload, Metadata metadata) implements Message<E, EventMessage<E>> {

    public static <E extends Event> EventMessage<E> from(E payload, Metadata metadata) {
        return new EventMessage<>(MessageId.generate(), SubjectIdResolver.resolve(payload), payload, metadata);
    }

    public EventMessage {
        requireNonNull(messageId);
        requireNonNull(subjectId);
        requireNonNull(payload);
        requireNonNull(metadata);
    }

    @Override
    public EventMessage<E> withMetadata(Metadata newMetadata) {
        return new EventMessage<>(messageId, subjectId, payload, newMetadata);
    }

    @Override
    public EventMessage<E> andMetadata(Metadata newMetadata) {
        return new EventMessage<>(messageId, subjectId, payload, metadata.and(newMetadata));
    }
}
