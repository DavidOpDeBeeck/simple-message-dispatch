package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.api.message.MessageId.generate;
import static app.dodb.smd.eventstore.storage.EventLog.EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventStorageTest {

    private static final EventLog OUTBOX = EventLog.outbox("tickets");

    @Test
    void store_assignsSequenceNumbersToNewEvents() {
        var storage = new InMemoryEventStorage();
        storage.store(EVENTS, event(null));
        storage.store(EVENTS, event(null));

        assertThat(storage.events())
            .extracting(SerializedEvent::sequenceNumber)
            .containsExactly(1L, 2L);
    }

    @Test
    void load_returnsOrderedEventsAfterSequenceNumberUpToLimit() {
        var first = event(1L);
        var third = event(3L);
        var storage = new InMemoryEventStorage(third, first);

        try (var events = storage.load(EVENTS, 1L, 1)) {
            assertThat(events.hasNext()).isTrue();
            assertThat(events.next()).isSameAs(third);
            assertThat(events.hasNext()).isFalse();
        }
    }

    @Test
    void store_afterSeededEvents_continuesAfterHighestSequenceNumber() {
        var storage = new InMemoryEventStorage(event(3L));

        storage.store(EVENTS, event(null));

        assertThat(storage.events())
            .extracting(SerializedEvent::sequenceNumber)
            .containsExactly(3L, 4L);
    }

    @Test
    void load_returnsOnlyEventsOfRequestedLog() {
        var storage = new InMemoryEventStorage();
        var inEvents = event(null);
        storage.store(EVENTS, inEvents);
        storage.store(OUTBOX, event(null));

        try (var events = storage.load(EVENTS, 0L, 10)) {
            assertThat(events.next().messageId()).isEqualTo(inEvents.messageId());
            assertThat(events.hasNext()).isFalse();
        }
    }

    @Test
    void storeIfAbsent_sameMessageInSameLog_storesOnce() {
        var storage = new InMemoryEventStorage();
        var event = event(null);

        var first = storage.storeIfAbsent(OUTBOX, event);
        var second = storage.storeIfAbsent(OUTBOX, event);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(storage.events(OUTBOX)).hasSize(1);
    }

    @Test
    void storeIfAbsent_sameMessageInOtherLog_storesInBoth() {
        var storage = new InMemoryEventStorage();
        var event = event(null);
        storage.store(EVENTS, event);

        var stored = storage.storeIfAbsent(OUTBOX, event);

        assertThat(stored).isTrue();
        assertThat(storage.events()).hasSize(2);
    }

    @Test
    void countStoredInOtherLogs_countsOnlyOtherLogsBetweenBounds() {
        var storage = new InMemoryEventStorage();
        storage.store(EVENTS, event(1L));
        storage.store(OUTBOX, event(2L));
        storage.store(EVENTS, event(3L));
        storage.store(OUTBOX, event(4L));
        storage.store(EVENTS, event(5L));

        var count = storage.countStoredInOtherLogs(EVENTS, 1L, 5L);

        assertThat(count).isEqualTo(2L);
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
