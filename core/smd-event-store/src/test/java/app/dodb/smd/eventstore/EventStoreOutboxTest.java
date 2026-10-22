package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.eventstore.InMemoryEventStoreFixture.RecordingSink;
import app.dodb.smd.eventstore.InMemoryEventStoreFixture.RecordingSubscriber;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.TokenState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.TicketEvent.ticketEvent;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.storage.EventLog.EVENTS;
import static java.time.Duration.ofHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class EventStoreOutboxTest {

    @Test
    void outbox_committedSends_forwardsInStoreOrder() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var sink = new RecordingSink();
            var outbox = fixture.eventStore.outbox("tickets", sink);
            var opened = ticketEvent("ticket-1", "opened");
            var otherOpened = ticketEvent("ticket-2", "opened");
            var closed = ticketEvent("ticket-1", "closed");

            fixture.transactions.doInTransaction(() -> {
                outbox.send(opened);
                outbox.send(otherOpened);
                outbox.send(closed);
            });

            await().untilAsserted(() -> assertThat(sink.received()).extracting(message -> message.messageId())
                .containsExactly(opened.messageId(), otherOpened.messageId(), closed.messageId()));
        }
    }

    @Test
    void outbox_rolledBackTransaction_forwardsNothing() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var sink = new RecordingSink();
            var outbox = fixture.eventStore.outbox("tickets", sink);
            var failure = new IllegalStateException("Publishing transaction failed");
            var committed = ticketEvent("ticket-2", "opened");

            assertThatThrownBy(() -> fixture.transactions.doInTransaction(() -> {
                outbox.send(ticketEvent("ticket-1", "opened"));
                throw failure;
            })).isSameAs(failure);
            fixture.transactions.doInTransaction(() -> outbox.send(committed));

            await().untilAsserted(() -> assertThat(sink.received()).extracting(message -> message.messageId())
                .containsExactly(committed.messageId()));
        }
    }

    @Test
    void outbox_publicationsOnlySentToInlet_areNotForwarded() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            fixture.eventStore.inlet().send(ticketEvent("ticket-1", "opened"));
            var sink = new RecordingSink();
            var outbox = fixture.eventStore.outbox("tickets", sink);
            var forwarded = ticketEvent("ticket-1", "closed");

            outbox.send(forwarded);
            fixture.eventStore.inlet().send(ticketEvent("ticket-1", "reopened"));

            await().untilAsserted(() -> assertThat(sink.received()).extracting(message -> message.messageId())
                .containsExactly(forwarded.messageId()));
        }
    }

    @Test
    void outbox_entries_areNotDeliveredOnOutlet() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var sink = new RecordingSink();
            var outbox = fixture.eventStore.outbox("tickets", sink);
            var subscriber = new RecordingSubscriber("ticket-view");
            fixture.eventStore.outlet().subscribe(subscriber);
            var published = ticketEvent("ticket-1", "opened");

            outbox.send(ticketEvent("ticket-1", "forwarded only"));
            fixture.eventStore.inlet().send(published);

            await().untilAsserted(() -> {
                assertThat(sink.changes()).containsExactly("forwarded only");
                assertThat(subscriber.received()).extracting(message -> message.messageId()).containsExactly(published.messageId());
            });
        }
    }

    @Test
    void outbox_forward_sendsMessageWithoutAttemptMetadata() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var sink = new RecordingSink();
            var outbox = fixture.eventStore.outbox("tickets", sink);
            var sent = ticketEvent("ticket-1", "opened");

            outbox.send(sent);

            await().untilAsserted(() -> assertThat(sink.received()).containsExactly(sent));
        }
    }

    @Test
    void outbox_sinkFailsBeyondMaxRetries_keepsRetryingWithoutAbandoning() {
        var failuresLeft = new AtomicInteger(4);
        try (var fixture = new InMemoryEventStoreFixture()) {
            var sink = new RecordingSink(message -> failuresLeft.getAndDecrement() > 0);
            var outbox = fixture.eventStore.outbox("tickets", sink);

            outbox.send(ticketEvent("ticket-1", "opened"));
            outbox.send(ticketEvent("ticket-1", "closed"));

            await().untilAsserted(() -> assertThat(sink.changes()).containsExactly("opened", "closed"));
            assertThat(fixture.sequences.eventSequenceState(EventLog.outbox("tickets"), "tickets", "ticket-1"))
                .hasValueSatisfying(state -> assertThat(state.status()).isEqualTo(ACTIVE));
        }
    }

    @Test
    void outbox_sinkFailsForOneSubject_forwardsOtherSubjects() {
        var ticketOneUnavailable = new AtomicBoolean(true);
        try (var fixture = new InMemoryEventStoreFixture(fixed(ofHours(1)))) {
            var sink = new RecordingSink(message -> ticketOneUnavailable.get() && ((TicketEvent) message.payload()).ticketId().equals("ticket-1"));
            var outbox = fixture.eventStore.outbox("tickets", sink);

            outbox.send(ticketEvent("ticket-1", "first opened"));
            outbox.send(ticketEvent("ticket-2", "second opened"));
            outbox.send(ticketEvent("ticket-1", "first closed"));

            await().untilAsserted(() -> assertThat(sink.changes()).containsExactly("second opened"));
            assertThat(fixture.sequences.eventSequenceState(EventLog.outbox("tickets"), "tickets", "ticket-1"))
                .hasValueSatisfying(state -> assertThat(state.errorCount()).isEqualTo(1));
        }
    }

    @Test
    void outbox_forward_runsStoreSinkInterceptorsAroundWrappedSink() {
        var interceptedSinks = new CopyOnWriteArrayList<EventSink>();
        EventSinkInterceptor recordingInterceptor = new EventSinkInterceptor() {
            @Override
            public <E extends Event> void intercept(EventSinkContext<E> context, EventSinkInterceptorChain<E> chain) {
                interceptedSinks.add(context.sink());
                chain.proceed(context);
            }
        };
        try (var fixture = new InMemoryEventStoreFixture(recordingInterceptor)) {
            var sink = new RecordingSink();
            var outbox = fixture.eventStore.outbox("tickets", sink);

            outbox.send(ticketEvent("ticket-1", "opened"));

            await().untilAsserted(() -> assertThat(sink.changes()).containsExactly("opened"));
            assertThat(interceptedSinks).containsExactly(sink);
        }
    }

    @Test
    void outbox_reportsEventStoreDelivery() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var outbox = fixture.eventStore.outbox("tickets", new RecordingSink());

            assertThat(outbox.delivery()).isEqualTo(EventStore.DELIVERY);
        }
    }

    @Test
    void outbox_duplicateName_fails() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            fixture.eventStore.outbox("tickets", new RecordingSink());

            assertThatThrownBy(() -> fixture.eventStore.outbox("tickets", new RecordingSink()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event store already has an outbox named 'tickets'");
        }
    }

    @Test
    void outbox_blankName_fails() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            assertThatThrownBy(() -> fixture.eventStore.outbox(" ", new RecordingSink()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event log requires a non-blank outbox name; got ' '");
        }
    }

    @Test
    void outlet_eventsInterleavedWithOutboxEntries_deliversWithoutWaitingForGap() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var outbox = fixture.eventStore.outbox("tickets", new RecordingSink());
            var subscriber = new RecordingSubscriber("ticket-view");
            fixture.eventStore.outlet().subscribe(subscriber);

            fixture.eventStore.inlet().send(ticketEvent("ticket-1", "opened"));
            outbox.send(ticketEvent("ticket-1", "forwarded only"));
            fixture.eventStore.inlet().send(ticketEvent("ticket-1", "closed"));

            await().untilAsserted(() -> assertThat(subscriber.changes()).containsExactly("opened", "closed"));
            assertThat(fixture.tokens.tokenState(EVENTS, "ticket-view").flatMap(TokenState::gapSequenceNumber)).isEmpty();
        }
    }

    @Test
    void outlet_positionMissingFromEveryLog_waitsForGap() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            fixture.storage.store(EVENTS, fixture.serialized(ticketEvent("ticket-1", "opened"), 1L));
            fixture.storage.store(EVENTS, fixture.serialized(ticketEvent("ticket-1", "closed"), 3L));
            var subscriber = new RecordingSubscriber("ticket-view");

            fixture.eventStore.outlet().subscribe(subscriber);

            await().untilAsserted(() -> assertThat(fixture.tokens.tokenState(EVENTS, "ticket-view").flatMap(TokenState::gapSequenceNumber)).contains(2L));
            assertThat(subscriber.changes()).containsExactly("opened");
        }
    }
}
