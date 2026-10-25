package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSubscriber;

import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class EventSubscriberStub implements EventSubscriber {

    private final String name;
    private final Consumer<EventMessage<? extends Event>> eventHandler;

    public EventSubscriberStub(String name, Consumer<EventMessage<? extends Event>> eventHandler) {
        this.name = requireNonNull(name);
        this.eventHandler = requireNonNull(eventHandler);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        eventHandler.accept(eventMessage);
    }
}
