package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;

import java.util.ArrayList;
import java.util.List;

import static app.dodb.smd.api.utils.ExceptionUtils.rethrow;

public class SynchronousEventChannel implements EventChannel {

    private final List<EventChannelListener> listeners = new ArrayList<>();

    public SynchronousEventChannel() {
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        try {
            for (EventChannelListener listener : listeners) {
                listener.on(eventMessage);
            }
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Override
    public void subscribe(EventChannelListener listener) {
        listeners.add(listener);
    }
}
