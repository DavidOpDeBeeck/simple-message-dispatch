package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.channel.EventChannelListener;

import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class EventChannelListenerStub implements EventChannelListener {

    private final String processingGroup;
    private final Consumer<EventMessage<? extends Event>> eventHandler;

    public EventChannelListenerStub(String processingGroup, Consumer<EventMessage<? extends Event>> eventHandler) {
        this.processingGroup = requireNonNull(processingGroup);
        this.eventHandler = requireNonNull(eventHandler);
    }

    @Override
    public String processingGroup() {
        return processingGroup;
    }

    @Override
    public <E extends Event> void on(EventMessage<E> eventMessage) {
        eventHandler.accept(eventMessage);
    }
}
