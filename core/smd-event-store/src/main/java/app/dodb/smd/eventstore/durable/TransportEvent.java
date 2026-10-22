package app.dodb.smd.eventstore.durable;

import app.dodb.smd.eventstore.storage.SerializedEvent;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record TransportEvent(SerializedEvent event, Optional<OutboxPosition> position) {

    public TransportEvent {
        requireNonNull(event);
        requireNonNull(position);
        if (event.subjectId().isPresent() != position.isPresent()) {
            throw new IllegalArgumentException("A transported event carries an outbox position exactly when it has a subject: messageId="
                + event.messageId().value() + ", subjectId=" + event.subjectId().orElse(null) + ", position=" + position.orElse(null));
        }
    }
}
