package app.dodb.smd.serialization;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.message.MessageId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record SerializedEvent(MessageId messageId,
                              Optional<Subject> subject,
                              String eventType,
                              byte[] serializedPayload,
                              byte[] serializedMetadata,
                              Instant createdAt) {

    public SerializedEvent {
        requireNonNull(messageId);
        requireNonNull(subject);
        requireNonNull(eventType);
        requireNonNull(serializedPayload);
        requireNonNull(serializedMetadata);
        requireNonNull(createdAt);
    }
}
