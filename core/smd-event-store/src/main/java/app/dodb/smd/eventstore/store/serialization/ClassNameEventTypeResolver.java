package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.event.Event;

public class ClassNameEventTypeResolver implements EventTypeResolver {

    @Override
    public String eventTypeFor(Event event) {
        return event.getClass().getName();
    }

    @Override
    public Class<? extends Event> eventClassFor(String eventType) throws EventTypeResolutionException {
        try {
            var eventClass = Class.forName(eventType);
            if (!Event.class.isAssignableFrom(eventClass)) {
                throw new EventTypeResolutionException("Event type does not implement Event: " + eventType);
            }
            return eventClass.asSubclass(Event.class);
        } catch (ClassNotFoundException e) {
            throw new EventTypeResolutionException("Event type not found: " + eventType, e);
        }
    }
}
