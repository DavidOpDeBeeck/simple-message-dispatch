package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.AggregateRevision;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static app.dodb.smd.api.utils.ValidationUtils.requirePositive;
import static java.util.Objects.requireNonNull;

public class InMemoryEventStorage implements EventStorage {

    private final NavigableMap<Long, StoredEvent> events = new TreeMap<>();
    private final Set<MessageId> eventIds = new HashSet<>();
    private final Map<AggregateStreamId, NavigableMap<Long, StoredEvent>> aggregateStreams = new HashMap<>();
    private long nextGlobalPosition = 1L;

    public InMemoryEventStorage(StoredEvent... events) {
        for (var event : requireNonNull(events)) {
            store(event);
        }
    }

    @Override
    public synchronized void append(NewEvent event) {
        store(atNextGlobalPosition(event, event.aggregateId()));
    }

    @Override
    public synchronized void append(NewEvent event, AggregateRevision revision) {
        var streamId = new AggregateStreamId(revision.aggregateType(), revision.aggregateId());
        var stream = aggregateStreams.computeIfAbsent(streamId, _ -> new TreeMap<>());
        if (stream.containsKey(revision.version())) {
            throw new ConcurrentAggregateChangeException(revision.aggregateType(), revision.aggregateId(), revision.expectedVersion());
        }

        var storedEvent = store(atNextGlobalPosition(event, Optional.of(revision.aggregateId())));
        stream.put(revision.version(), storedEvent);
    }

    private StoredEvent store(StoredEvent event) {
        var globalPosition = event.globalPosition();
        if (globalPosition < 1L) {
            throw new EventStorageException("Event global position must be greater than zero: " + globalPosition);
        }
        if (events.containsKey(globalPosition)) {
            throw new EventStorageException("Event global position already stored: " + globalPosition);
        }
        if (!eventIds.add(event.eventId())) {
            throw new EventStorageException("Event ID already stored: " + event.eventId());
        }

        events.put(globalPosition, event);
        nextGlobalPosition = Math.max(nextGlobalPosition, globalPosition + 1L);
        return event;
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
    public synchronized List<StoredEvent> loadAggregateStream(String aggregateType, String aggregateId) {
        var stream = aggregateStreams.get(new AggregateStreamId(aggregateType, aggregateId));
        return stream == null ? List.of() : List.copyOf(stream.values());
    }

    public synchronized List<StoredEvent> events() {
        return List.copyOf(events.values());
    }

    private StoredEvent atNextGlobalPosition(NewEvent event, Optional<String> aggregateId) {
        return new StoredEvent(
            nextGlobalPosition,
            event.eventId(),
            aggregateId,
            event.eventType(),
            event.payload(),
            event.metadata(),
            event.occurredAt()
        );
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

    private record AggregateStreamId(String aggregateType, String aggregateId) {
    }
}
