package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.Cursor;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.EventStorageException;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.eventstore.storage.StoredEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

import static app.dodb.smd.api.utils.ValidationUtils.requirePositive;
import static java.util.Objects.requireNonNull;

public class InMemoryEventStorage implements EventStorage {

    private final NavigableMap<Long, StoredEvent> events = new TreeMap<>();
    private final Set<MessageId> eventIds = new HashSet<>();
    private final Map<Subject, NavigableMap<Long, StoredEvent>> subjectStreams = new HashMap<>();
    private long nextGlobalPosition = 1L;

    public InMemoryEventStorage(StoredEvent... events) {
        for (var event : requireNonNull(events)) {
            store(event);
        }
    }

    @Override
    public synchronized void append(NewEvent event) {
        if (event.subjectVersion().isPresent() && subjectStream(event.subject().orElseThrow()).containsKey(event.subjectVersion().get())) {
            throw new ConcurrentAggregateChangeException(event.subject().orElseThrow(), event.subjectVersion().get() - 1L);
        }
        store(atNextGlobalPosition(event));
    }

    private void store(StoredEvent event) {
        var globalPosition = event.globalPosition();
        if (globalPosition < 1L) {
            throw new EventStorageException("Event global position must be greater than zero: " + globalPosition);
        }
        if (events.containsKey(globalPosition)) {
            throw new EventStorageException("Event global position already stored: " + globalPosition);
        }
        if (eventIds.contains(event.eventId())) {
            throw new EventStorageException("Event ID already stored: " + event.eventId());
        }
        if (event.subjectVersion().isPresent() && subjectStream(event.subject().orElseThrow()).containsKey(event.subjectVersion().get())) {
            throw new EventStorageException("Subject version already stored: subject=%s, subjectVersion=%d"
                .formatted(event.subject().orElseThrow(), event.subjectVersion().get()));
        }

        events.put(globalPosition, event);
        eventIds.add(event.eventId());
        if (event.subjectVersion().isPresent()) {
            subjectStream(event.subject().orElseThrow()).put(event.subjectVersion().get(), event);
        }
        nextGlobalPosition = Math.max(nextGlobalPosition, globalPosition + 1L);
    }

    @Override
    public synchronized Cursor<StoredEvent> load(long afterGlobalPosition, int limit) {
        requirePositive("Event store load limit", limit);

        var eventsToLoad = events.tailMap(afterGlobalPosition, false)
            .values()
            .stream()
            .limit(limit)
            .toList();
        return cursor(new ArrayDeque<>(eventsToLoad));
    }

    @Override
    public synchronized List<StoredEvent> loadSubjectStream(Subject subject) {
        var stream = subjectStreams.get(subject);
        return stream == null ? List.of() : List.copyOf(stream.values());
    }

    public synchronized List<StoredEvent> events() {
        return List.copyOf(events.values());
    }

    private StoredEvent atNextGlobalPosition(NewEvent event) {
        return new StoredEvent(
            nextGlobalPosition,
            event.eventId(),
            event.subject(),
            event.subjectVersion(),
            event.eventType(),
            event.payload(),
            event.metadata(),
            event.occurredAt()
        );
    }

    private NavigableMap<Long, StoredEvent> subjectStream(Subject subject) {
        return subjectStreams.computeIfAbsent(subject, _ -> new TreeMap<>());
    }

    private static Cursor<StoredEvent> cursor(Deque<StoredEvent> events) {
        return new Cursor<>() {

            @Override
            public boolean hasNext() {
                return !events.isEmpty();
            }

            @Override
            public StoredEvent next() {
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
