package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;

class RejectingEventSerializer implements EventSerializer {

    @Override
    public <E extends Event> SerializedEvent serialize(EventMessage<E> eventMessage) {
        throw new UnsupportedOperationException("Rejected event: " + eventMessage.payload().getClass().getSimpleName());
    }

    @Override
    public <E extends Event> EventMessage<E> deserialize(SerializedEvent serializedEvent) {
        throw new UnsupportedOperationException("Rejected stored event: " + serializedEvent.messageId());
    }
}
