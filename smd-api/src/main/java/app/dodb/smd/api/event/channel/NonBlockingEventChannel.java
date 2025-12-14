package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class NonBlockingEventChannel implements EventChannel {

    public static NonBlockingEventChannel usingVirtualThreads() {
        return new NonBlockingEventChannel(newVirtualThreadPerTaskExecutor());
    }

    public static NonBlockingEventChannel using(ExecutorService executorService) {
        return new NonBlockingEventChannel(executorService);
    }

    private final ExecutorService executorService;
    private final List<EventChannelListener> listeners = new ArrayList<>();

    private NonBlockingEventChannel(ExecutorService executorService) {
        this.executorService = requireNonNull(executorService);
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        listeners.forEach(listener -> executorService.submit(() -> listener.on(eventMessage)));
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        listeners.add(listener);
    }
}
