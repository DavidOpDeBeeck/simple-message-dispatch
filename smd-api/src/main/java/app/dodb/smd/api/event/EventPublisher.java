package app.dodb.smd.api.event;

public interface EventPublisher {

    <E extends Event> void publish(E event);

    <E extends Event> void publish(EventMessage<E> eventMessage);
}
