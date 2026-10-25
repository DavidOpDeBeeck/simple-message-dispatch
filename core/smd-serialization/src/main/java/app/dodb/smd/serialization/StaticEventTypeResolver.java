package app.dodb.smd.serialization;

import app.dodb.smd.api.event.Event;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class StaticEventTypeResolver implements EventTypeResolver {

    private final Map<String, Class<? extends Event>> eventClassByIdentifier;
    private final Map<Class<? extends Event>, String> eventTypeByEventClass;

    public StaticEventTypeResolver(Map<String, Class<? extends Event>> eventClassByEventType) {
        this.eventClassByIdentifier = requireNonNull(eventClassByEventType);
        this.eventTypeByEventClass = indexByEventClass(eventClassByEventType);
    }

    @Override
    public Optional<String> eventTypeFor(Event event) {
        return Optional.ofNullable(eventTypeByEventClass.get(event.getClass()));
    }

    @Override
    public Optional<Class<? extends Event>> eventClassFor(String eventType) {
        return Optional.ofNullable(eventClassByIdentifier.get(eventType));
    }

    private static Map<Class<? extends Event>, String> indexByEventClass(Map<String, Class<? extends Event>> eventClassByEventType) {
        var eventTypeByEventClass = new HashMap<Class<? extends Event>, String>();
        for (var entry : eventClassByEventType.entrySet()) {
            var duplicate = eventTypeByEventClass.putIfAbsent(entry.getValue(), entry.getKey());
            if (duplicate != null) {
                throw new IllegalArgumentException("Event class %s is mapped to event types '%s' and '%s'; map each event class to one event type"
                    .formatted(entry.getValue().getName(), duplicate, entry.getKey()));
            }
        }
        return eventTypeByEventClass;
    }
}
