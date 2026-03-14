package app.dodb.smd.eventstore.store;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

public interface EventSerializer {

    <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage);

    <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent);
}
