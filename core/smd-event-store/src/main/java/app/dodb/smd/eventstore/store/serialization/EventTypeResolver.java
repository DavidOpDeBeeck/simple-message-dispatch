package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.event.Event;

public interface EventTypeResolver {

    String eventTypeFor(Event event) throws EventTypeResolutionException;

    Class<? extends Event> eventClassFor(String eventType) throws EventTypeResolutionException;
}
