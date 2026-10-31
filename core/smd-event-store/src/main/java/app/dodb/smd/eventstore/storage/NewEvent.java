package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.serialization.SerializedEvent;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record NewEvent(MessageId eventId,
                       Optional<Subject> subject,
                       Optional<Long> subjectVersion,
                       String eventType,
                       byte[] payload,
                       byte[] metadata,
                       Instant occurredAt) {

    public NewEvent {
        requireNonNull(eventId);
        requireNonNull(subject);
        requireNonNull(subjectVersion);
        requireNonNull(eventType);
        requireNonNull(payload);
        requireNonNull(metadata);
        requireNonNull(occurredAt);
        if (subjectVersion.isPresent() && subject.isEmpty()) {
            throw new IllegalArgumentException("Invalid event: a subject version requires a subject. eventId=%s, subjectVersion=%d"
                .formatted(eventId, subjectVersion.get()));
        }
    }

    public static NewEvent from(SerializedEvent serializedEvent) {
        return from(serializedEvent, Optional.empty());
    }

    public static NewEvent from(SerializedEvent serializedEvent, long subjectVersion) {
        return from(serializedEvent, Optional.of(subjectVersion));
    }

    private static NewEvent from(SerializedEvent serializedEvent, Optional<Long> subjectVersion) {
        return new NewEvent(
            serializedEvent.messageId(),
            serializedEvent.subject(),
            subjectVersion,
            serializedEvent.eventType(),
            serializedEvent.serializedPayload(),
            serializedEvent.serializedMetadata(),
            serializedEvent.createdAt()
        );
    }
}
