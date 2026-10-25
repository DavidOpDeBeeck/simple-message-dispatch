package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.message.MessageId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record StoredEvent(long globalPosition,
                          MessageId eventId,
                          Optional<String> aggregateId,
                          String eventType,
                          byte[] payload,
                          byte[] metadata,
                          Instant occurredAt) {

    public StoredEvent {
        requireNonNull(eventId);
        requireNonNull(aggregateId);
        requireNonNull(eventType);
        requireNonNull(payload);
        requireNonNull(metadata);
        requireNonNull(occurredAt);
    }
}
