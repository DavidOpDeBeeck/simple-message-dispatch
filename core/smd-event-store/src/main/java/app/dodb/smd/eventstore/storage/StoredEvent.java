package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.serialization.SerializedEvent;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record StoredEvent(long globalPosition,
                          MessageId eventId,
                          Optional<Subject> subject,
                          Optional<Long> subjectVersion,
                          String eventType,
                          byte[] payload,
                          byte[] metadata,
                          Instant occurredAt) {

    public StoredEvent {
        requireNonNull(eventId);
        requireNonNull(subject);
        requireNonNull(subjectVersion);
        requireNonNull(eventType);
        requireNonNull(payload);
        requireNonNull(metadata);
        requireNonNull(occurredAt);
        if (subjectVersion.isPresent() && subject.isEmpty()) {
            throw new IllegalArgumentException("Invalid stored event: a subject version requires a subject. eventId=%s, subjectVersion=%d"
                .formatted(eventId, subjectVersion.get()));
        }
    }

    public SerializedEvent serializedEvent() {
        return new SerializedEvent(eventId, subject, eventType, payload, metadata, occurredAt);
    }
}
