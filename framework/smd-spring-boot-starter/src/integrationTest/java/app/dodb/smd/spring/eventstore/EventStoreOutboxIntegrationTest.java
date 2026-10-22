package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import app.dodb.smd.test.EventSinkStub;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStoreOutboxIntegrationTest {

    @Test
    void outbox_committedTransaction_forwardsAfterCommit() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start();
             var eventStore = fixture.createEventStore(scheduler)) {
            var transactions = fixture.bean(TransactionProvider.class);
            var sink = new EventSinkStub();
            var outbox = eventStore.outbox("tickets", sink);
            var message = message("outbox-commit");

            // When
            transactions.doInTransaction(() -> outbox.send(message));
            scheduler.poll();

            // Then
            assertThat(sink.getEventMessages()).containsExactly(message);
        }
    }

    @Test
    void outbox_rolledBackTransaction_forwardsNothing() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start();
             var eventStore = fixture.createEventStore(scheduler)) {
            var transactions = fixture.bean(TransactionProvider.class);
            var sink = new EventSinkStub();
            var outbox = eventStore.outbox("tickets", sink);
            var failure = new IllegalStateException("Publishing transaction failed");

            // When
            assertThatThrownBy(() -> transactions.doInTransaction(() -> {
                outbox.send(message("outbox-rollback"));
                transactions.defer(() -> {
                    throw failure;
                });
            })).isSameAs(failure);
            scheduler.poll();

            // Then
            assertThat(fixture.storedEvents()).isEmpty();
            assertThat(sink.getEventMessages()).isEmpty();
        }
    }

    @Test
    void outbox_entriesInterleavedWithEvents_outletAndOutboxContinueWithoutGap() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start();
             var eventStore = fixture.createEventStore(scheduler)) {
            var transactions = fixture.bean(TransactionProvider.class);
            var sink = new EventSinkStub();
            var outbox = eventStore.outbox("tickets", sink);
            var received = new CopyOnWriteArrayList<EventMessage<?>>();
            eventStore.outlet().subscribe(new EventSubscriberStub("projection", received::add));
            var opened = message("interleaved");
            var forwarded = message("interleaved");
            var closed = message("interleaved");

            // When
            transactions.doInTransaction(() -> eventStore.inlet().send(opened));
            transactions.doInTransaction(() -> outbox.send(forwarded));
            transactions.doInTransaction(() -> eventStore.inlet().send(closed));
            scheduler.poll();

            // Then
            assertThat(received).extracting(EventMessage::messageId).containsExactly(opened.messageId(), closed.messageId());
            assertThat(sink.getEventMessages()).containsExactly(forwarded);
            assertThat(fixture.tokenState("projection").flatMap(TokenState::gapSequenceNumber)).isEmpty();
        }
    }

    @Test
    void inbox_redeliveredMessage_storesOnceWithoutUsingSequencePosition() throws Exception {
        // Given
        var scheduler = new ManualPollingScheduler();
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start();
             var eventStore = fixture.createEventStore(scheduler)) {
            var transactions = fixture.bean(TransactionProvider.class);
            var broker = new SynchronousEventDispatcher();
            var received = new CopyOnWriteArrayList<EventMessage<?>>();
            eventStore.inbox(broker.outlet()).subscribe(new EventSubscriberStub("billing", received::add));
            var redelivered = message("inbox");
            var next = message("inbox");

            // When
            transactions.doInNewTransaction(() -> broker.send(redelivered));
            transactions.doInNewTransaction(() -> broker.send(redelivered));
            transactions.doInNewTransaction(() -> broker.send(next));
            scheduler.poll();

            // Then
            assertThat(received).extracting(EventMessage::messageId).containsExactly(redelivered.messageId(), next.messageId());
            assertThat(fixture.storedEvents()).extracting(SerializedEvent::sequenceNumber).satisfies(sequenceNumbers ->
                assertThat(sequenceNumbers.get(1)).isEqualTo(sequenceNumbers.get(0) + 1));
        }
    }

    private static EventMessage<TestEventWithSubjectId> message(String subjectId) {
        return EventMessage.from(new TestEventWithSubjectId(subjectId), new Metadata(null, Instant.parse("2026-10-22T10:15:30Z"), null));
    }
}
