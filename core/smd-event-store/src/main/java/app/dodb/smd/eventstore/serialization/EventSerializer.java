package app.dodb.smd.eventstore.serialization;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.eventstore.storage.SerializedEvent;

public interface EventSerializer {

    <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage);

    <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent);
}
