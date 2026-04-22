package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.store.SerializedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

import static java.time.ZoneId.systemDefault;
import static java.util.Objects.requireNonNull;

public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper objectMapper;
    private final EventTypeResolver eventTypeResolver;

    public JacksonEventSerializer(ObjectMapper objectMapper) {
        this(objectMapper, new ClassNameEventTypeResolver());
    }

    public JacksonEventSerializer(ObjectMapper objectMapper, EventTypeResolver eventTypeResolver) {
        this.objectMapper = requireNonNull(objectMapper);
        this.eventTypeResolver = requireNonNull(eventTypeResolver);
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
                eventType,
                payloadBytes,
                metadataBytes,
                eventMessage.metadata().timestamp().atZone(systemDefault()).toInstant()
            );
        } catch (EventTypeResolutionException e) {
            throw new EventSerializationException("Failed to resolve event type for event: " + eventMessage.messageId(), e);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("Failed to serialize event: " + eventMessage.messageId(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
        try {
            var eventClass = eventTypeResolver.eventClassFor(serializedEvent.eventType());
            var payload = (E) objectMapper.readValue(serializedEvent.serializedPayload(), eventClass);
            var metadata = objectMapper.readValue(serializedEvent.serializedMetadata(), Metadata.class);

            return new EventMessage<>(serializedEvent.messageId(), payload, metadata);
        } catch (EventTypeResolutionException e) {
            throw new EventSerializationException("Failed to resolve event type: " + serializedEvent.eventType(), e);
        } catch (IOException e) {
            throw new EventSerializationException("Failed to deserialize event: " + serializedEvent.messageId(), e);
        }
    }

    public static class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
