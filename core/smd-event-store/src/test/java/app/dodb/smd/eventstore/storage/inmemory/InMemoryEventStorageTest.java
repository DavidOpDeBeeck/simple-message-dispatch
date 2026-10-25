package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.eventstore.storage.StoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.api.message.MessageId.generate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class InMemoryEventStorageTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-04-23T10:15:30Z");

    @Test
    void append_assignsGlobalPositionsToNewEvents() {
        var storage = new InMemoryEventStorage();
        storage.append(newEvent(Optional.empty()));
        storage.append(newEvent(Optional.empty()));

        assertThat(storage.events())
            .extracting(StoredEvent::globalPosition)
            .containsExactly(1L, 2L);
    }

    @Test
    void append_eventWithAggregateId_keepsAggregateId() {
        var storage = new InMemoryEventStorage();

        storage.append(newEvent(Optional.of("ticket-1")));

        assertThat(storage.events())
            .extracting(StoredEvent::aggregateId)
            .containsExactly(Optional.of("ticket-1"));
    }

    @Test
    void load_returnsOrderedEventsAfterGlobalPositionUpToLimit() {
        var first = storedEvent(1L);
        var third = storedEvent(3L);
        var storage = new InMemoryEventStorage(third, first);

        try (var events = storage.load(1L, 1)) {
            assertThat(events.hasNext()).isTrue();
            assertThat(events.next()).isSameAs(third);
            assertThat(events.hasNext()).isFalse();
        }
    }

    @Test
    void append_afterSeededEvents_continuesAfterHighestGlobalPosition() {
        var storage = new InMemoryEventStorage(storedEvent(3L));

        storage.append(newEvent(Optional.empty()));

        assertThat(storage.events())
            .extracting(StoredEvent::globalPosition)
            .containsExactly(3L, 4L);
    }

    @Test
    void loadAggregateStream_returnsEventsOfOneStreamInVersionOrder() {
        var storage = new InMemoryEventStorage();
        var second = newEvent(Optional.empty());
        var first = newEvent(Optional.empty());
        storage.append(second, new AggregateRevision("ticket", "ticket-1", 2L));
        storage.append(newEvent(Optional.empty()), new AggregateRevision("ticket", "ticket-2", 1L));
        storage.append(first, new AggregateRevision("ticket", "ticket-1", 1L));

        var stream = storage.loadAggregateStream("ticket", "ticket-1");

        assertThat(stream)
            .extracting(StoredEvent::eventId, StoredEvent::aggregateId)
            .containsExactly(
                tuple(first.eventId(), Optional.of("ticket-1")),
                tuple(second.eventId(), Optional.of("ticket-1"))
            );
    }

    @Test
    void append_existingAggregateVersion_throwsConcurrentAggregateChange() {
        var storage = new InMemoryEventStorage();
        storage.append(newEvent(Optional.empty()), new AggregateRevision("ticket", "ticket-1", 1L));

        assertThatThrownBy(() -> storage.append(newEvent(Optional.empty()), new AggregateRevision("ticket", "ticket-1", 1L)))
            .isInstanceOfSatisfying(ConcurrentAggregateChangeException.class,
                exception -> assertThat(exception.expectedVersion()).isZero());

        assertThat(storage.events()).hasSize(1);
    }

    private static NewEvent newEvent(Optional<String> aggregateId) {
        return new NewEvent(generate(), aggregateId, "test-event", new byte[0], new byte[0], OCCURRED_AT);
    }

    private static StoredEvent storedEvent(long globalPosition) {
        return new StoredEvent(globalPosition, generate(), Optional.of("test-aggregate"), "test-event", new byte[0], new byte[0], OCCURRED_AT);
    }
}
