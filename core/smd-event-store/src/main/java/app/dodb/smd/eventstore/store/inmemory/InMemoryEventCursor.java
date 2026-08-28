package app.dodb.smd.eventstore.store.inmemory;

import app.dodb.smd.eventstore.store.Cursor;
import app.dodb.smd.eventstore.store.SerializedEvent;

import java.util.Deque;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

public class InMemoryEventCursor implements Cursor<SerializedEvent> {

    private final Deque<SerializedEvent> events;

    public InMemoryEventCursor(Deque<SerializedEvent> events) {
        this.events = requireNonNull(events);
    }

    @Override
    public boolean hasNext() {
        return !events.isEmpty();
    }

    @Override
    public SerializedEvent next() {
        if (hasNext()) {
            return events.poll();
        }
        throw new NoSuchElementException("No more events in cursor");
    }

    @Override
    public void close() {
        // no resources to release
    }
}
