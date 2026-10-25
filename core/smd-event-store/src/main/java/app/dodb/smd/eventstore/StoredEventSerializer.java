package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.eventstore.storage.StoredEvent;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

final class StoredEventSerializer {

    private final EventSerializer eventSerializer;

    StoredEventSerializer(EventSerializer eventSerializer) {
        this.eventSerializer = requireNonNull(eventSerializer);
    }

    <E extends Event> NewEvent serialize(EventMessage<E> eventMessage, Optional<AggregateRevision> revision) {
        var serializedEvent = eventSerializer.serialize(eventMessage);
        var aggregateId = revision.map(AggregateRevision::aggregateId).or(serializedEvent::subjectId);
        return new NewEvent(
            serializedEvent.messageId(),
            aggregateId,
            serializedEvent.eventType(),
            serializedEvent.serializedPayload(),
            serializedEvent.serializedMetadata(),
            serializedEvent.createdAt()
        );
    }

    <E extends Event> EventMessage<E> deserialize(StoredEvent storedEvent) {
        return eventSerializer.deserialize(new SerializedEvent(
            storedEvent.eventId(),
            storedEvent.globalPosition(),
            storedEvent.aggregateId(),
            storedEvent.eventType(),
            storedEvent.payload(),
            storedEvent.metadata(),
            storedEvent.occurredAt()
        ));
    }
}
