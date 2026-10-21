package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer.EventSerializationException;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

final class EventUpcasterChain {

    private final Map<String, EventUpcaster> upcasterByEventType;

    EventUpcasterChain(List<EventUpcaster> upcasters, EventTypeResolver eventTypeResolver) {
        requireNonNull(upcasters);
        requireNonNull(eventTypeResolver);
        this.upcasterByEventType = indexByEventType(upcasters);
        rejectCycles(upcasters);
        rejectResolvedEventTypes(upcasters, eventTypeResolver);
        rejectUnresolvedUpcastedEventTypes(upcasters, eventTypeResolver);
    }

    boolean upcasts(String eventType) {
        return upcasterByEventType.containsKey(eventType);
    }

    UpcastedPayload upcast(MessageId messageId, String eventType, ObjectNode payload) {
        var upcastedEventType = eventType;
        var upcastedPayload = payload;
        var upcaster = upcasterByEventType.get(eventType);
        while (upcaster != null) {
            upcastedPayload = upcaster.upcast(upcastedPayload);
            if (upcastedPayload == null) {
                throw new EventSerializationException("%s returned null for event %s stored as '%s'".formatted(upcaster, messageId, eventType));
            }
            upcastedEventType = upcaster.upcastedEventType();
            upcaster = upcasterByEventType.get(upcastedEventType);
        }
        return new UpcastedPayload(upcastedEventType, upcastedPayload);
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
            var eventClass = resolve(eventTypeResolver, upcaster.eventType());
            if (eventClass.isPresent()) {
                throw new IllegalArgumentException("Event type '%s' is upcast by %s but also resolved to %s by %s; map only the current event type name"
                    .formatted(upcaster.eventType(), upcaster, eventClass.get().getName(), eventTypeResolver.getClass().getSimpleName()));
            }
        }
    }

    private void rejectUnresolvedUpcastedEventTypes(List<EventUpcaster> upcasters, EventTypeResolver eventTypeResolver) {
        for (var upcaster : upcasters) {
            var upcastedEventType = upcaster.upcastedEventType();
            if (!upcasts(upcastedEventType) && resolve(eventTypeResolver, upcastedEventType).isEmpty()) {
                throw new IllegalArgumentException("%s produces event type '%s', which has no upcaster and is not resolved by %s"
                    .formatted(upcaster, upcastedEventType, eventTypeResolver.getClass().getSimpleName()));
            }
        }
    }

    private static Optional<Class<? extends Event>> resolve(EventTypeResolver eventTypeResolver, String eventType) {
        try {
            return Optional.of(eventTypeResolver.eventClassFor(eventType));
        } catch (EventTypeResolutionException e) {
            return Optional.empty();
        }
    }

    record UpcastedPayload(String eventType, ObjectNode payload) {

        UpcastedPayload {
            requireNonNull(eventType);
            requireNonNull(payload);
        }
    }
}
