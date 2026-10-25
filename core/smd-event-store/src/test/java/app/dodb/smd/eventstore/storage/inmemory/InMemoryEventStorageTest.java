package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.serialization.SerializedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.api.message.MessageId.generate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEventStorageTest {

    @Test
    void store_assignsSequenceNumbersToNewEvents() {
        var storage = new InMemoryEventStorage();
        storage.store(event(null));
        storage.store(event(null));

        assertThat(storage.events())
            .extracting(SerializedEvent::sequenceNumber)
            .containsExactly(1L, 2L);
    }

    @Test
    void load_returnsOrderedEventsAfterSequenceNumberUpToLimit() {
        var first = event(1L);
        var third = event(3L);
        var storage = new InMemoryEventStorage(third, first);

        try (var events = storage.load(1L, 1)) {
            assertThat(events.hasNext()).isTrue();
            assertThat(events.next()).isSameAs(third);
            assertThat(events.hasNext()).isFalse();
        }
    }

    @Test
    void store_afterSeededEvents_continuesAfterHighestSequenceNumber() {
        var storage = new InMemoryEventStorage(event(3L));

        storage.store(event(null));

        assertThat(storage.events())
            .extracting(SerializedEvent::sequenceNumber)
            .containsExactly(3L, 4L);
    }

    @Test
    void loadAggregateStream_returnsEventsOfOneStreamInVersionOrder() {
        var storage = new InMemoryEventStorage();
        var second = event(null);
        var first = event(null);
        storage.store(second, new AggregateRevision("ticket", "ticket-1", 2L));
        storage.store(event(null), new AggregateRevision("ticket", "ticket-2", 1L));
        storage.store(first, new AggregateRevision("ticket", "ticket-1", 1L));

        var stream = storage.loadAggregateStream("ticket", "ticket-1");

        assertThat(stream)
            .extracting(SerializedEvent::messageId)
            .containsExactly(first.messageId(), second.messageId());
    }

    @Test
    void store_existingAggregateVersion_throwsConcurrentAggregateChange() {
        var storage = new InMemoryEventStorage();
        storage.store(event(null), new AggregateRevision("ticket", "ticket-1", 1L));

        assertThatThrownBy(() -> storage.store(event(null), new AggregateRevision("ticket", "ticket-1", 1L)))
            .isInstanceOfSatisfying(ConcurrentAggregateChangeException.class,
                exception -> assertThat(exception.expectedVersion()).isZero());

        assertThat(storage.events()).hasSize(1);
    }

    private SerializedEvent event(Long sequenceNumber) {
        return new SerializedEvent(
            generate(),
            sequenceNumber,
            Optional.of("test-subjectId"),
            "test-event",
            new byte[0],
            new byte[0],
            Instant.parse("2026-04-23T10:15:30Z")
        );
    }
}
