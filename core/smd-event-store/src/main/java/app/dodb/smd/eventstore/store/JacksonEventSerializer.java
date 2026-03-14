package app.dodb.smd.eventstore.store;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

import static java.time.ZoneId.systemDefault;
import static java.util.Objects.requireNonNull;

public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper objectMapper;

    public JacksonEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper);
    }

    @Override
    public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
        try {
            var payloadBytes = objectMapper.writeValueAsBytes(eventMessage.payload());
            var metadataBytes = objectMapper.writeValueAsBytes(eventMessage.metadata());

            return new SerializedEvent(
                eventMessage.messageId(),
                null, // sequence number is assigned by database
                eventMessage.payload().getClass().getName(),
                payloadBytes,
                metadataBytes,
                eventMessage.metadata().timestamp().atZone(systemDefault()).toInstant()
            );
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("Failed to serialize event: " + eventMessage.messageId(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
        try {
            var eventType = Class.forName(serializedEvent.eventType());
            var payload = (E) objectMapper.readValue(serializedEvent.serializedPayload(), eventType);
            var metadata = objectMapper.readValue(serializedEvent.serializedMetadata(), Metadata.class);

            return new EventMessage<>(serializedEvent.messageId(), payload, metadata);
        } catch (ClassNotFoundException e) {
            throw new EventSerializationException("Event type not found: " + serializedEvent.eventType(), e);
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
