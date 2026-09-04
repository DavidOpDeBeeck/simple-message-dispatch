package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.storage.SerializedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.api.message.MessageId.generate;
import static org.assertj.core.api.Assertions.assertThat;

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
