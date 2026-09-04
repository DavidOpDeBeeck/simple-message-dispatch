package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.Cursor;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.EventStorageException;
import app.dodb.smd.eventstore.storage.SerializedEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

public class InMemoryEventStorage implements EventStorage {

    private final NavigableMap<Long, SerializedEvent> events = new TreeMap<>();
    private final Set<MessageId> messageIds = new HashSet<>();
    private long nextSequenceNumber = 1L;

    public InMemoryEventStorage(SerializedEvent... events) {
        for (var event : requireNonNull(events)) {
            store(event);
        }
    }

    @Override
    public synchronized void store(SerializedEvent event) {
        var eventToStore = withSequenceNumber(requireNonNull(event));
        var sequenceNumber = eventToStore.sequenceNumber();

        if (sequenceNumber < 1L) {
            throw new EventStorageException("Event sequence number must be greater than zero: " + sequenceNumber);
        }
        if (events.containsKey(sequenceNumber)) {
            throw new EventStorageException("Event sequence number already stored: " + sequenceNumber);
        }
        if (!messageIds.add(eventToStore.messageId())) {
            throw new EventStorageException("Event message ID already stored: " + eventToStore.messageId());
        }

        events.put(sequenceNumber, eventToStore);
        nextSequenceNumber = Math.max(nextSequenceNumber, sequenceNumber + 1L);
    }

    @Override
    public synchronized Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException();
        }

        var eventsToLoad = events.tailMap(lastProcessedSequenceNumber, false)
            .values()
            .stream()
            .limit(limit)
            .toList();
        return cursor(new ArrayDeque<>(eventsToLoad));
    }

    public synchronized List<SerializedEvent> events() {
        return List.copyOf(events.values());
    }

    private SerializedEvent withSequenceNumber(SerializedEvent event) {
        if (event.sequenceNumber() != null) {
            return event;
        }
        return new SerializedEvent(
            event.messageId(),
            nextSequenceNumber,
            event.subjectId(),
            event.eventType(),
            event.serializedPayload(),
            event.serializedMetadata(),
            event.createdAt()
        );
    }

    private static Cursor<SerializedEvent> cursor(Deque<SerializedEvent> events) {
        return new Cursor<>() {

            @Override
            public boolean hasNext() {
                return !events.isEmpty();
            }

            @Override
            public SerializedEvent next() {
                if (hasNext()) {
                    return events.remove();
                }
                throw new NoSuchElementException("No more events in cursor");
            }

            @Override
            public void close() {
            }
        };
    }
}
