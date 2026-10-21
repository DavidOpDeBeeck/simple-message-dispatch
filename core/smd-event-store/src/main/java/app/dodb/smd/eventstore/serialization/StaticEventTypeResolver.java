package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.event.Event;

import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class StaticEventTypeResolver implements EventTypeResolver {

    private final Map<String, Class<? extends Event>> eventClassByIdentifier;
    private final Map<Class<? extends Event>, String> eventTypeByEventClass;

    public StaticEventTypeResolver(Map<String, Class<? extends Event>> eventClassByEventType) {
        this.eventClassByIdentifier = requireNonNull(eventClassByEventType);
        this.eventTypeByEventClass = indexByEventClass(eventClassByEventType);
    }

    @Override
    public String eventTypeFor(Event event) throws EventTypeResolutionException {
        if (eventTypeByEventClass.containsKey(event.getClass())) {
            return eventTypeByEventClass.get(event.getClass());
        }
        throw new EventTypeResolutionException("Event class not found: " + event.getClass());
    }

    @Override
    public Class<? extends Event> eventClassFor(String eventType) throws EventTypeResolutionException {
        if (eventClassByIdentifier.containsKey(eventType)) {
            return eventClassByIdentifier.get(eventType);
        }
        throw new EventTypeResolutionException("Event type not found: " + eventType);
    }

    private static Map<Class<? extends Event>, String> indexByEventClass(Map<String, Class<? extends Event>> eventClassByEventType) {
        var eventTypeByEventClass = new HashMap<Class<? extends Event>, String>();
        for (var entry : eventClassByEventType.entrySet()) {
            var duplicate = eventTypeByEventClass.putIfAbsent(entry.getValue(), entry.getKey());
            if (duplicate != null) {
                throw new IllegalArgumentException("Event class %s is mapped to event types '%s' and '%s'; map only the current event type name and upcast older names with EventUpcaster"
                    .formatted(entry.getValue().getName(), duplicate, entry.getKey()));
            }
        }
        return eventTypeByEventClass;
    }
}
