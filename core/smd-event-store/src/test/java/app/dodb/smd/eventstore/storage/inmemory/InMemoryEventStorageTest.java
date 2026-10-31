package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.eventstore.aggregate.ConcurrentAggregateChangeException;
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
    private static final Subject TICKET = new Subject("ticket", "ticket-1");

    @Test
    void append_assignsGlobalPositionsToNewEvents() {
        var storage = new InMemoryEventStorage();
        storage.append(newEvent(Optional.empty(), Optional.empty()));
        storage.append(newEvent(Optional.empty(), Optional.empty()));

        assertThat(storage.events())
            .extracting(StoredEvent::globalPosition)
            .containsExactly(1L, 2L);
    }

    @Test
    void append_subjectEvent_keepsSubjectWithoutVersion() {
        var storage = new InMemoryEventStorage();

        storage.append(newEvent(Optional.of(TICKET), Optional.empty()));

        assertThat(storage.events())
            .extracting(StoredEvent::subject, StoredEvent::subjectVersion)
            .containsExactly(tuple(Optional.of(TICKET), Optional.empty()));
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

        storage.append(newEvent(Optional.empty(), Optional.empty()));

        assertThat(storage.events())
            .extracting(StoredEvent::globalPosition)
            .containsExactly(3L, 4L);
    }

    @Test
    void loadSubjectStream_returnsVersionedEventsOfOneSubjectInVersionOrder() {
        var storage = new InMemoryEventStorage();
        var second = newEvent(Optional.of(TICKET), Optional.of(2L));
        var first = newEvent(Optional.of(TICKET), Optional.of(1L));
        storage.append(second);
        storage.append(newEvent(Optional.of(new Subject("ticket", "ticket-2")), Optional.of(1L)));
        storage.append(newEvent(Optional.of(new Subject("account", "ticket-1")), Optional.of(1L)));
        storage.append(newEvent(Optional.of(TICKET), Optional.empty()));
        storage.append(first);

        var stream = storage.loadSubjectStream(TICKET);

        assertThat(stream)
            .extracting(StoredEvent::eventId, StoredEvent::subjectVersion)
            .containsExactly(
                tuple(first.eventId(), Optional.of(1L)),
                tuple(second.eventId(), Optional.of(2L))
            );
    }

    @Test
    void loadSubjectStream_seededVersionedEvents_returnsThem() {
        var seeded = storedEvent(1L, Optional.of(TICKET), Optional.of(1L));
        var storage = new InMemoryEventStorage(seeded);

        var stream = storage.loadSubjectStream(TICKET);

        assertThat(stream).containsExactly(seeded);
    }

    @Test
    void append_versionOfSeededEvent_throwsConcurrentAggregateChange() {
        var storage = new InMemoryEventStorage(storedEvent(1L, Optional.of(TICKET), Optional.of(1L)));

        assertThatThrownBy(() -> storage.append(newEvent(Optional.of(TICKET), Optional.of(1L))))
            .isInstanceOf(ConcurrentAggregateChangeException.class);

        assertThat(storage.events()).hasSize(1);
    }

    @Test
    void append_existingSubjectVersion_throwsConcurrentAggregateChange() {
        var storage = new InMemoryEventStorage();
        storage.append(newEvent(Optional.of(TICKET), Optional.of(1L)));

        assertThatThrownBy(() -> storage.append(newEvent(Optional.of(TICKET), Optional.of(1L))))
            .isInstanceOfSatisfying(ConcurrentAggregateChangeException.class, exception -> {
                assertThat(exception.aggregateType()).isEqualTo("ticket");
                assertThat(exception.aggregateId()).isEqualTo("ticket-1");
                assertThat(exception.expectedVersion()).isZero();
            });

        assertThat(storage.events()).hasSize(1);
    }

    private static NewEvent newEvent(Optional<Subject> subject, Optional<Long> subjectVersion) {
        return new NewEvent(generate(), subject, subjectVersion, "test-event", new byte[0], new byte[0], OCCURRED_AT);
    }

    private static StoredEvent storedEvent(long globalPosition) {
        return storedEvent(globalPosition, Optional.of(new Subject("test-aggregate", "test-aggregate-1")), Optional.empty());
    }

    private static StoredEvent storedEvent(long globalPosition, Optional<Subject> subject, Optional<Long> subjectVersion) {
        return new StoredEvent(globalPosition, generate(), subject, subjectVersion, "test-event", new byte[0], new byte[0], OCCURRED_AT);
    }
}
