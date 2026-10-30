package app.dodb.smd.api.event;

import app.dodb.smd.api.metadata.Metadata;

import java.util.function.UnaryOperator;

public interface EventPublisher {

    <E extends Event> void publish(E event);

    <E extends Event> void publish(E event, Metadata metadata);

    <E extends Event> void publish(E event, UnaryOperator<Metadata> metadataUpdate);

    <E extends Event> void publish(EventMessage<E> eventMessage);
}
