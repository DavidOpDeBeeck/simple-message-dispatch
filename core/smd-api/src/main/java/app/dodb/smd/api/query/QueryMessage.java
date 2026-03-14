package app.dodb.smd.api.query;

import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;

import static java.util.Objects.requireNonNull;

public record QueryMessage<R, C extends Query<R>>(MessageId messageId, C payload, Metadata metadata) implements Message<C, QueryMessage<R, C>> {

    public static <R, C extends Query<R>> QueryMessage<R, C> from(C payload, Metadata metadata) {
        return new QueryMessage<>(MessageId.generate(), payload, metadata);
    }

    public QueryMessage {
        requireNonNull(messageId);
        requireNonNull(payload);
        requireNonNull(metadata);
    }

    @Override
    public QueryMessage<R, C> withMetadata(Metadata newMetadata) {
        return new QueryMessage<>(messageId, payload, newMetadata);
    }

    @Override
    public QueryMessage<R, C> andMetadata(Metadata newMetadata) {
        return new QueryMessage<>(messageId, payload, metadata.and(newMetadata));
    }
}
