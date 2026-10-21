package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.Cursor;
import app.dodb.smd.eventstore.storage.EventLog;
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

    private final NavigableMap<Long, StoredEvent> events = new TreeMap<>();
    private final Set<LoggedMessageId> messageIds = new HashSet<>();
    private long nextSequenceNumber = 1L;

    public InMemoryEventStorage(SerializedEvent... events) {
        for (var event : requireNonNull(events)) {
            store(EventLog.EVENTS, event);
        }
    }

    @Override
    public synchronized void store(EventLog log, SerializedEvent event) {
        requireNonNull(log);
        var eventToStore = withSequenceNumber(requireNonNull(event));
        var sequenceNumber = eventToStore.sequenceNumber();

        if (sequenceNumber < 1L) {
            throw new EventStorageException("Event sequence number must be greater than zero: " + sequenceNumber);
        }
        if (events.containsKey(sequenceNumber)) {
            throw new EventStorageException("Event sequence number already stored: " + sequenceNumber);
        }
        if (!messageIds.add(new LoggedMessageId(log, eventToStore.messageId()))) {
            throw new EventStorageException("Event message ID already stored: log=" + log.name() + ", messageId=" + eventToStore.messageId());
        }

        events.put(sequenceNumber, new StoredEvent(log, eventToStore));
        nextSequenceNumber = Math.max(nextSequenceNumber, sequenceNumber + 1L);
    }

    @Override
    public synchronized boolean storeIfAbsent(EventLog log, SerializedEvent event) {
        if (messageIds.contains(new LoggedMessageId(requireNonNull(log), event.messageId()))) {
            return false;
        }
        store(log, event);
        return true;
    }

    @Override
    public synchronized Cursor<SerializedEvent> load(EventLog log, long lastProcessedSequenceNumber, int limit) {
        requireNonNull(log);
        if (limit <= 0) {
            throw new IllegalArgumentException("Event load limit must be greater than zero; got " + limit);
        }

        var eventsToLoad = events.tailMap(lastProcessedSequenceNumber, false)
            .values()
            .stream()
            .filter(stored -> stored.log().equals(log))
            .map(StoredEvent::event)
            .limit(limit)
            .toList();
        return cursor(new ArrayDeque<>(eventsToLoad));
    }

    @Override
    public synchronized long countStoredInOtherLogs(EventLog log, long afterSequenceNumber, long beforeSequenceNumber) {
        requireNonNull(log);
        if (beforeSequenceNumber <= afterSequenceNumber) {
            return 0;
        }
        return events.subMap(afterSequenceNumber, false, beforeSequenceNumber, false)
            .values()
            .stream()
            .filter(stored -> !stored.log().equals(log))
            .count();
    }

    public synchronized List<SerializedEvent> events() {
        return events.values().stream().map(StoredEvent::event).toList();
    }

    public synchronized List<SerializedEvent> events(EventLog log) {
        return events.values().stream()
            .filter(stored -> stored.log().equals(log))
            .map(StoredEvent::event)
            .toList();
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

    private record StoredEvent(EventLog log, SerializedEvent event) {

        StoredEvent {
            requireNonNull(log);
            requireNonNull(event);
        }
    }

    private record LoggedMessageId(EventLog log, MessageId messageId) {

        LoggedMessageId {
            requireNonNull(log);
            requireNonNull(messageId);
        }
    }
}
