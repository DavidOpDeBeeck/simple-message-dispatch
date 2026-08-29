package app.dodb.smd.api.event;

import app.dodb.smd.api.metadata.Metadata;

public interface EventPublisher {

    <E extends Event> void publish(E event);

    <E extends Event> void publish(E event, Metadata metadata);

    <E extends Event> void publish(EventMessage<E> eventMessage);
}
