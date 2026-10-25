package app.dodb.smd.serialization;

import app.dodb.smd.api.event.Event;

import java.util.Optional;

public class ClassNameEventTypeResolver implements EventTypeResolver {

    @Override
    public Optional<String> eventTypeFor(Event event) {
        return Optional.of(event.getClass().getName());
    }

    @Override
    public Optional<Class<? extends Event>> eventClassFor(String eventType) {
        try {
            var eventClass = Class.forName(eventType);
            if (Event.class.isAssignableFrom(eventClass)) {
                return Optional.of(eventClass.asSubclass(Event.class));
            }
            return Optional.empty();
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
