package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.message.MessageId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record NewEvent(MessageId eventId,
                       Optional<String> aggregateId,
                       String eventType,
                       byte[] payload,
                       byte[] metadata,
                       Instant occurredAt) {

    public NewEvent {
        requireNonNull(eventId);
        requireNonNull(aggregateId);
        requireNonNull(eventType);
        requireNonNull(payload);
        requireNonNull(metadata);
        requireNonNull(occurredAt);
    }
}
