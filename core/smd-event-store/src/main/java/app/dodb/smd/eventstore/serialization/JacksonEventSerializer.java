package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper objectMapper;
    private final EventTypeResolver eventTypeResolver;
    private final EventUpcasterChain upcasterChain;

    public JacksonEventSerializer(ObjectMapper objectMapper) {
        this(objectMapper, new ClassNameEventTypeResolver());
    }

    public JacksonEventSerializer(ObjectMapper objectMapper, EventTypeResolver eventTypeResolver) {
        this(objectMapper, eventTypeResolver, List.of());
    }

    public JacksonEventSerializer(ObjectMapper objectMapper, EventTypeResolver eventTypeResolver, List<EventUpcaster> upcasters) {
        this.objectMapper = requireNonNull(objectMapper);
        this.eventTypeResolver = requireNonNull(eventTypeResolver);
        this.upcasterChain = new EventUpcasterChain(upcasters, eventTypeResolver);
    }

    @Override
    public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
        try {
            var eventType = eventTypeResolver.eventTypeFor(eventMessage.payload());
            var payloadBytes = objectMapper.writeValueAsBytes(eventMessage.payload());
            var metadataBytes = objectMapper.writeValueAsBytes(eventMessage.metadata());

            return new SerializedEvent(
                eventMessage.messageId(),
                null, // sequence number is assigned by database
                eventMessage.subjectId(),
                eventType,
                payloadBytes,
                metadataBytes,
                eventMessage.metadata().timestamp()
            );
        } catch (EventTypeResolutionException e) {
            throw new EventSerializationException("Failed to resolve event type for event: " + eventMessage.messageId(), e);
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to serialize event: " + eventMessage.messageId(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
        try {
            var payload = (E) readPayload(serializedEvent);
            var metadata = objectMapper.readValue(serializedEvent.serializedMetadata(), Metadata.class);

            return new EventMessage<>(serializedEvent.messageId(), serializedEvent.subjectId(), payload, metadata);
        } catch (EventTypeResolutionException e) {
            throw new EventSerializationException("Failed to resolve event type: " + serializedEvent.eventType(), e);
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to deserialize event: " + serializedEvent.messageId(), e);
        }
    }

    private Event readPayload(SerializedEvent serializedEvent) throws EventTypeResolutionException {
        var eventType = serializedEvent.eventType();
        if (!upcasterChain.upcasts(eventType)) {
            return objectMapper.readValue(serializedEvent.serializedPayload(), eventTypeResolver.eventClassFor(eventType));
        }

        if (!(objectMapper.readTree(serializedEvent.serializedPayload()) instanceof ObjectNode storedPayload)) {
            throw new EventSerializationException("Cannot upcast event %s stored as '%s': payload is not a JSON object".formatted(serializedEvent.messageId(), eventType));
        }
        var upcasted = upcasterChain.upcast(serializedEvent.messageId(), eventType, storedPayload);
        return objectMapper.treeToValue(upcasted.payload(), eventTypeResolver.eventClassFor(upcasted.eventType()));
    }

    public static class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message) {
            super(message);
        }

        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
