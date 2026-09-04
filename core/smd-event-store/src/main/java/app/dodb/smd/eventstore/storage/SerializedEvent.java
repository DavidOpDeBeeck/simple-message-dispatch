package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.message.MessageId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record SerializedEvent(MessageId messageId,
                              Long sequenceNumber,
                              Optional<String> subjectId,
                              String eventType,
                              byte[] serializedPayload,
                              byte[] serializedMetadata,
                              Instant createdAt) {

    public SerializedEvent {
        requireNonNull(messageId);
        // sequenceNumber can be null when serializing new events (before DB insert)
        requireNonNull(subjectId);
        requireNonNull(eventType);
        requireNonNull(serializedPayload);
        requireNonNull(serializedMetadata);
        requireNonNull(createdAt);
    }

    public SerializedEvent(MessageId messageId,
                           Long sequenceNumber,
                           String eventType,
                           byte[] serializedPayload,
                           byte[] serializedMetadata,
                           Instant createdAt) {
        this(messageId, sequenceNumber, Optional.empty(), eventType, serializedPayload, serializedMetadata, createdAt);
    }
}
