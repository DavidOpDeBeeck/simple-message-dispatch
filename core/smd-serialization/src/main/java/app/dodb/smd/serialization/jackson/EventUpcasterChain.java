package app.dodb.smd.serialization.jackson;

import app.dodb.smd.serialization.EventTypeResolver;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer.EventSerializationException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

final class EventUpcasterChain {

    private final ObjectMapper objectMapper;
    private final Map<String, EventUpcaster> upcasterByEventType;

    EventUpcasterChain(ObjectMapper objectMapper, EventTypeResolver eventTypeResolver, List<EventUpcaster> upcasters) {
        this.objectMapper = requireNonNull(objectMapper);
        requireNonNull(eventTypeResolver);
        requireNonNull(upcasters);
        this.upcasterByEventType = indexByEventType(upcasters);
        rejectCycles(upcasters);
        rejectResolvedEventTypes(upcasters, eventTypeResolver);
        rejectUnresolvedUpcastedEventTypes(upcasters, eventTypeResolver);
    }

    SerializedEvent upcast(SerializedEvent serializedEvent) {
        var eventType = serializedEvent.eventType();
        var upcaster = upcasterByEventType.get(eventType);
        if (upcaster == null) {
            return serializedEvent;
        }
        if (!(objectMapper.readTree(serializedEvent.serializedPayload()) instanceof ObjectNode storedPayload)) {
            throw new EventSerializationException("Cannot upcast event %s stored as '%s': payload is not a JSON object"
                .formatted(serializedEvent.messageId(), eventType));
        }

        var upcastedEventType = eventType;
        var upcastedPayload = storedPayload;
        while (upcaster != null) {
            upcastedPayload = upcaster.upcast(upcastedPayload);
            if (upcastedPayload == null) {
                throw new EventSerializationException("%s returned null for event %s stored as '%s'"
                    .formatted(upcaster, serializedEvent.messageId(), eventType));
            }
            upcastedEventType = upcaster.upcastedEventType();
            upcaster = upcasterByEventType.get(upcastedEventType);
        }

        return new SerializedEvent(
            serializedEvent.messageId(),
            serializedEvent.sequenceNumber(),
            serializedEvent.subjectId(),
            upcastedEventType,
            // This is not optimal because we parse the payload twice and should be improved in the future, but it is acceptable for now because upcasting is not expected to be a performance bottleneck
            objectMapper.writeValueAsBytes(upcastedPayload),
            serializedEvent.serializedMetadata(),
            serializedEvent.createdAt()
        );
    }

    private static Map<String, EventUpcaster> indexByEventType(List<EventUpcaster> upcasters) {
        var upcasterByEventType = new HashMap<String, EventUpcaster>();
        for (var upcaster : upcasters) {
            var duplicate = upcasterByEventType.putIfAbsent(upcaster.eventType(), upcaster);
            if (duplicate != null) {
                throw new IllegalArgumentException("Duplicate event upcasters for event type '%s': %s, %s; register one upcaster per event type"
                    .formatted(upcaster.eventType(), duplicate, upcaster));
            }
        }
        return upcasterByEventType;
    }

    private void rejectCycles(List<EventUpcaster> upcasters) {
        for (var upcaster : upcasters) {
            var path = new LinkedHashSet<String>();
            path.add(upcaster.eventType());
            for (var next = upcaster; next != null; next = upcasterByEventType.get(next.upcastedEventType())) {
                if (!path.add(next.upcastedEventType())) {
                    throw new IllegalArgumentException("Event upcasters form a cycle: %s -> %s".formatted(String.join(" -> ", path), next.upcastedEventType()));
                }
            }
        }
    }

    private static void rejectResolvedEventTypes(List<EventUpcaster> upcasters, EventTypeResolver eventTypeResolver) {
        for (var upcaster : upcasters) {
            var eventClass = eventTypeResolver.eventClassFor(upcaster.eventType());
            if (eventClass.isPresent()) {
                throw new IllegalArgumentException("Event type '%s' is upcast by %s but can also be resolved to %s; map only the current event type name"
                    .formatted(upcaster.eventType(), upcaster, eventClass.get().getName()));
            }
        }
    }

    private void rejectUnresolvedUpcastedEventTypes(List<EventUpcaster> upcasters, EventTypeResolver eventTypeResolver) {
        for (var upcaster : upcasters) {
            var upcastedEventType = upcaster.upcastedEventType();
            if (!upcasterByEventType.containsKey(upcastedEventType) && eventTypeResolver.eventClassFor(upcastedEventType).isEmpty()) {
                throw new IllegalArgumentException("%s produces event type '%s', which has no upcaster and can not be resolved"
                    .formatted(upcaster, upcastedEventType));
            }
        }
    }
}
