package app.dodb.smd.api.event;

public interface EventHandlerBehaviour<E extends Event> {

    int order();

    String processingGroup();

    Class<E> eventType();

    void handle(EventMessage<E> eventMessage);
}
