package app.dodb.smd.eventstore.store.inmemory;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.store.Cursor;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.EventStorageException;
import app.dodb.smd.eventstore.store.SerializedEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import static app.dodb.smd.eventstore.utils.ValidationUtils.requireGreaterThanZero;
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
        var eventsToLoad = events.tailMap(lastProcessedSequenceNumber, false)
            .values()
            .stream()
            .limit(requireGreaterThanZero(limit))
            .toList();
        return new InMemoryEventCursor(new ArrayDeque<>(eventsToLoad));
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
}
