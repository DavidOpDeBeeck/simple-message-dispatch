package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.Cursor;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.EventStorageException;
import app.dodb.smd.serialization.SerializedEvent;

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

    private final NavigableMap<Long, SerializedEvent> events = new TreeMap<>();
    private final Set<MessageId> messageIds = new HashSet<>();
    private final Map<AggregateStreamId, NavigableMap<Long, SerializedEvent>> aggregateStreams = new HashMap<>();
    private long nextSequenceNumber = 1L;

    public InMemoryEventStorage(SerializedEvent... events) {
        for (var event : requireNonNull(events)) {
            store(event);
        }
    }

    @Override
    public synchronized void store(SerializedEvent event) {
        storeWithSequenceNumber(event);
    }

    @Override
    public synchronized void store(SerializedEvent event, AggregateRevision revision) {
        var streamId = new AggregateStreamId(revision.aggregateType(), revision.aggregateId());
        var stream = aggregateStreams.computeIfAbsent(streamId, _ -> new TreeMap<>());
        if (stream.containsKey(revision.version())) {
            throw new ConcurrentAggregateChangeException(revision.aggregateType(), revision.aggregateId(), revision.expectedVersion());
        }

        var storedEvent = storeWithSequenceNumber(withSubjectId(event, revision.aggregateId()));
        stream.put(revision.version(), storedEvent);
    }

    private SerializedEvent storeWithSequenceNumber(SerializedEvent event) {
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
        return eventToStore;
    }

    @Override
    public synchronized Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit) {
        requirePositive("Event store load limit", limit);

        var eventsToLoad = events.tailMap(lastProcessedSequenceNumber, false)
            .values()
            .stream()
            .limit(limit)
            .toList();
        return cursor(new ArrayDeque<>(eventsToLoad));
    }

    @Override
    public synchronized List<SerializedEvent> loadAggregateStream(String aggregateType, String aggregateId) {
        var stream = aggregateStreams.get(new AggregateStreamId(aggregateType, aggregateId));
        return stream == null ? List.of() : List.copyOf(stream.values());
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

    private static SerializedEvent withSubjectId(SerializedEvent event, String subjectId) {
        return new SerializedEvent(
            event.messageId(),
            event.sequenceNumber(),
            Optional.of(subjectId),
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

    private record AggregateStreamId(String aggregateType, String aggregateId) {
    }
}
