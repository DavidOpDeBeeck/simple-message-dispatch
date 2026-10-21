package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import app.dodb.smd.eventstore.InMemoryEventStoreFixture.RecordingSink;
import app.dodb.smd.eventstore.InMemoryEventStoreFixture.RecordingSubscriber;
import app.dodb.smd.eventstore.storage.EventLog;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.TicketEvent.ticketEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventStoreInboxTest {

    @Test
    void inbox_receivedMessage_deliversWithAttemptMetadata() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var subscriber = new RecordingSubscriber("billing");
            fixture.eventStore.inbox(broker.outlet()).subscribe(subscriber);
            var received = ticketEvent("ticket-1", "opened");

            broker.send(received);

            await().untilAsserted(() -> assertThat(subscriber.received()).singleElement().satisfies(message -> {
                assertThat(message.messageId()).isEqualTo(received.messageId());
                assertThat(message.payload()).isEqualTo(received.payload());
                assertThat(message.metadata().properties())
                    .containsEntry("tenant", "acme")
                    .containsEntry("subjectId", "ticket-1")
                    .containsEntry("errorCount", "0")
                    .containsKeys("processingId", "sequenceNumber");
            }));
        }
    }

    @Test
    void inbox_duplicateMessage_isDeliveredOnce() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var subscriber = new RecordingSubscriber("billing");
            fixture.eventStore.inbox(broker.outlet()).subscribe(subscriber);
            var redelivered = ticketEvent("ticket-1", "opened");

            broker.send(redelivered);
            broker.send(redelivered);
            broker.send(ticketEvent("ticket-1", "closed"));

            await().untilAsserted(() -> assertThat(subscriber.changes()).containsExactly("opened", "closed"));
            assertThat(fixture.storage.events(EventLog.inbox("billing"))).hasSize(2);
        }
    }

    @Test
    void inbox_subscribers_getSeparateLogs() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var inbox = fixture.eventStore.inbox(broker.outlet());
            var billing = new RecordingSubscriber("billing");
            var reporting = new RecordingSubscriber("reporting");
            inbox.subscribe(billing);
            inbox.subscribe(reporting);

            broker.send(ticketEvent("ticket-1", "opened"));

            await().untilAsserted(() -> {
                assertThat(billing.changes()).containsExactly("opened");
                assertThat(reporting.changes()).containsExactly("opened");
            });
            assertThat(fixture.storage.events(EventLog.inbox("billing"))).hasSize(1);
            assertThat(fixture.storage.events(EventLog.inbox("reporting"))).hasSize(1);
        }
    }

    @Test
    void inbox_handlerFails_retriesSubjectWithoutBlockingBroker() {
        var failuresLeft = new AtomicInteger(1);
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var subscriber = new RecordingSubscriber("billing", message -> failuresLeft.getAndDecrement() > 0);
            fixture.eventStore.inbox(broker.outlet()).subscribe(subscriber);

            broker.send(ticketEvent("ticket-1", "opened"));
            broker.send(ticketEvent("ticket-1", "closed"));

            await().untilAsserted(() -> assertThat(subscriber.changes()).containsExactly("opened", "closed"));
            assertThat(subscriber.received().getFirst().metadata().properties()).containsEntry("errorCount", "1");
        }
    }

    @Test
    void inbox_events_areNotDeliveredOnOutletOrForwardedByOutbox() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var inboxSubscriber = new RecordingSubscriber("billing");
            var outletSubscriber = new RecordingSubscriber("ticket-view");
            var outboxSink = new RecordingSink();
            fixture.eventStore.inbox(broker.outlet()).subscribe(inboxSubscriber);
            fixture.eventStore.outlet().subscribe(outletSubscriber);
            var outbox = fixture.eventStore.outbox("tickets", outboxSink);

            broker.send(ticketEvent("ticket-1", "received"));
            fixture.eventStore.inlet().send(ticketEvent("ticket-2", "published"));
            outbox.send(ticketEvent("ticket-2", "forwarded"));

            await().untilAsserted(() -> {
                assertThat(inboxSubscriber.changes()).containsExactly("received");
                assertThat(outletSubscriber.changes()).containsExactly("published");
                assertThat(outboxSink.changes()).containsExactly("forwarded");
            });
        }
    }

    @Test
    void inbox_closedSubscription_stopsReceiving() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var subscriber = new RecordingSubscriber("billing");
            var subscription = fixture.eventStore.inbox(broker.outlet()).subscribe(subscriber);

            subscription.close();
            broker.send(ticketEvent("ticket-1", "opened"));

            assertThat(fixture.storage.events()).isEmpty();
        }
    }

    @Test
    void inbox_insert_runsStoreSinkInterceptorsOnSourceThread() {
        var interceptedDeliveries = new CopyOnWriteArrayList<EventDelivery>();
        var interceptingThreads = new CopyOnWriteArrayList<Thread>();
        EventSinkInterceptor recordingInterceptor = new EventSinkInterceptor() {
            @Override
            public <E extends Event> void intercept(EventSinkContext<E> context, EventSinkInterceptorChain<E> chain) {
                interceptedDeliveries.add(context.sink().delivery());
                interceptingThreads.add(Thread.currentThread());
                chain.proceed(context);
            }
        };
        try (var fixture = new InMemoryEventStoreFixture(recordingInterceptor)) {
            var broker = new SynchronousEventDispatcher();
            fixture.eventStore.inbox(broker.outlet()).subscribe(new RecordingSubscriber("billing"));

            broker.send(ticketEvent("ticket-1", "opened"));

            assertThat(interceptedDeliveries).containsExactly(EventStore.DELIVERY);
            assertThat(interceptingThreads).containsExactly(Thread.currentThread());
        }
    }

    @Test
    void inbox_reportsEventStoreDelivery() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var inbox = fixture.eventStore.inbox(new SynchronousEventDispatcher().outlet());

            assertThat(inbox.delivery()).isEqualTo(EventStore.DELIVERY);
        }
    }

    @Test
    void durable_medium_publishesThroughOutboxAndConsumesThroughInbox() {
        try (var fixture = new InMemoryEventStoreFixture()) {
            var broker = new SynchronousEventDispatcher();
            var durable = fixture.eventStore.durable("ticket-sync", broker);
            var subscriber = new RecordingSubscriber("ticket-sync");
            durable.subscribe(subscriber);

            fixture.transactions.doInTransaction(() -> durable.send(ticketEvent("ticket-1", "opened")));

            await().untilAsserted(() -> assertThat(subscriber.changes()).containsExactly("opened"));
            assertThat(fixture.storage.events(EventLog.outbox("ticket-sync"))).hasSize(1);
            assertThat(fixture.storage.events(EventLog.inbox("ticket-sync"))).hasSize(1);
        }
    }
}
