package app.dodb.smd.serialization.jackson;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.EventTypeResolutionException;
import app.dodb.smd.serialization.EventTypeResolver;
import app.dodb.smd.serialization.SerializedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

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
            var metadataBytes = objectMapper.writeValueAsBytes(MetadataJson.from(eventMessage.metadata()));

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
            var eventClass = eventTypeResolver.eventClassFor(serializedEvent.eventType());
            var payload = (E) objectMapper.readValue(serializedEvent.serializedPayload(), eventClass);
            var metadata = objectMapper.readValue(serializedEvent.serializedMetadata(), MetadataJson.class).toMetadata();

            return new EventMessage<>(serializedEvent.messageId(), serializedEvent.subjectId(), payload, metadata);
        } catch (EventTypeResolutionException e) {
            throw new EventSerializationException("Failed to resolve event type: " + serializedEvent.eventType(), e);
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to deserialize event: " + serializedEvent.messageId(), e);
        }
    }

    record MetadataJson(Principal principal, Instant timestamp, MessageId parentMessageId, Map<String, String> properties) {

        static MetadataJson from(Metadata metadata) {
            return new MetadataJson(metadata.principal(), metadata.timestamp(), metadata.parentMessageId(), metadata.properties().asMap());
        }

        Metadata toMetadata() {
            return new Metadata(principal, timestamp, parentMessageId, properties);
        }
    }

    public static class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
