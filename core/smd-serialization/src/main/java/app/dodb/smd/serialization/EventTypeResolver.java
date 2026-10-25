package app.dodb.smd.serialization;

import app.dodb.smd.api.event.Event;

import java.util.Optional;

public interface EventTypeResolver {

    Optional<String> eventTypeFor(Event event);

    Optional<Class<? extends Event>> eventClassFor(String eventType);
}
