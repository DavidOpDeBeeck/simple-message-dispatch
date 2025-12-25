package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public class AsyncFireAndForgetEventChannel implements EventChannel {

    public static AsyncFireAndForgetEventChannel usingVirtualThreads() {
        return new AsyncFireAndForgetEventChannel(newVirtualThreadPerTaskExecutor());
    }

    public static AsyncFireAndForgetEventChannel using(ExecutorService executorService) {
        return new AsyncFireAndForgetEventChannel(executorService);
    }

    private final ExecutorService executorService;
    private final List<EventChannelListener> listeners = new ArrayList<>();

    private AsyncFireAndForgetEventChannel(ExecutorService executorService) {
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
