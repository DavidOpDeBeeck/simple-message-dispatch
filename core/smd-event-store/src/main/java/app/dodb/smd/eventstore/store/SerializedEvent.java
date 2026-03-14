package app.dodb.smd.eventstore.store;

import app.dodb.smd.api.message.MessageId;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public record SerializedEvent(MessageId messageId,
                              Long sequenceNumber,
                              String eventType,
                              byte[] serializedPayload,
                              byte[] serializedMetadata,
                              Instant createdAt) {

    public SerializedEvent {
        requireNonNull(messageId);
        // sequenceNumber can be null when serializing new events (before DB insert)
        requireNonNull(eventType);
        requireNonNull(serializedPayload);
        requireNonNull(serializedMetadata);
        requireNonNull(createdAt);
    }
}
