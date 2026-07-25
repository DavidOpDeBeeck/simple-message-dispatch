package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.event.Event;

import java.util.Map;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

public class StaticEventTypeResolver implements EventTypeResolver {

    private final Map<String, Class<? extends Event>> eventClassByIdentifier;
    private final Map<Class<? extends Event>, String> eventTypeByEventClass;

    public StaticEventTypeResolver(Map<String, Class<? extends Event>> eventClassByEventType) {
        this.eventClassByIdentifier = requireNonNull(eventClassByEventType);
        this.eventTypeByEventClass = eventClassByEventType.entrySet().stream()
            .collect(toMap(Map.Entry::getValue, Map.Entry::getKey));
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
}
