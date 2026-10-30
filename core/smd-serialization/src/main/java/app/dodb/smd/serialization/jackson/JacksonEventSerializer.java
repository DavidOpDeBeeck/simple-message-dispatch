package app.dodb.smd.serialization.jackson;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataProperties;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.EventTypeResolver;
import app.dodb.smd.serialization.SerializedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class JacksonEventSerializer implements EventSerializer {

    private final ObjectMapper objectMapper;
    private final EventTypeResolver eventTypeResolver;
    private final EventUpcasterChain upcasterChain;

    public JacksonEventSerializer(ObjectMapper objectMapper, EventTypeResolver eventTypeResolver, List<EventUpcaster> upcasters) {
        this.objectMapper = requireNonNull(objectMapper);
        this.eventTypeResolver = requireNonNull(eventTypeResolver);
        this.upcasterChain = new EventUpcasterChain(objectMapper, eventTypeResolver, upcasters);
    }

    @Override
    public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
        try {
            var eventType = eventTypeResolver.eventTypeFor(eventMessage.payload())
                .orElseThrow(() -> new EventSerializationException("Cannot serialize event %s: event class %s can not be resolved"
                    .formatted(eventMessage.messageId(), eventMessage.payload().getClass().getName())));
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
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to serialize event: " + eventMessage.messageId(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
        try {
            var payload = (E) readPayload(serializedEvent);
            var metadata = objectMapper.readValue(serializedEvent.serializedMetadata(), MetadataJson.class).toMetadata();

            return new EventMessage<>(serializedEvent.messageId(), serializedEvent.subjectId(), payload, metadata);
        } catch (JacksonException e) {
            throw new EventSerializationException("Failed to deserialize event: " + serializedEvent.messageId(), e);
        }
    }

    private Event readPayload(SerializedEvent serializedEvent) {
        var upcasted = upcasterChain.upcast(serializedEvent);
        var eventClass = eventTypeResolver.eventClassFor(upcasted.eventType())
            .orElseThrow(() -> new EventSerializationException("Cannot deserialize event %s: event type '%s' can not be resolved"
                .formatted(upcasted.messageId(), upcasted.eventType())));
        return objectMapper.readValue(upcasted.serializedPayload(), eventClass);
    }

    record MetadataJson(Principal principal, Instant timestamp, MessageId parentMessageId, Map<String, String> properties) {

        static MetadataJson from(Metadata metadata) {
            return new MetadataJson(metadata.principal(), metadata.timestamp(), metadata.parentMessageId(), metadata.properties().asMap());
        }

        Metadata toMetadata() {
            return new Metadata(principal, timestamp, parentMessageId, MetadataProperties.of(properties), MetadataProperties.empty());
        }
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
