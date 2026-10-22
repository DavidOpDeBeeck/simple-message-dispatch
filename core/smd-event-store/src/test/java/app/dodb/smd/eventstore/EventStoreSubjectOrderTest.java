package app.dodb.smd.eventstore;

import app.dodb.smd.eventstore.InMemoryEventStoreFixture.RecordingSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.TicketEvent.ticketEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventStoreSubjectOrderTest {

    @Test
    void outlet_backoffExpiringMidBatch_doesNotLetLaterEventOfSubjectOvertake() {
        // The first backoff check holds the failed event back; every later check finds the backoff expired.
        var backoffChecks = new AtomicInteger();
        RetryBackoffStrategy expiresAfterFirstCheck = _ -> backoffChecks.getAndIncrement() == 0 ? Duration.ofHours(1) : Duration.ZERO;
        var failuresLeft = new AtomicInteger(1);
        try (var fixture = new InMemoryEventStoreFixture(expiresAfterFirstCheck)) {
            var subscriber = new RecordingSubscriber("ticket-view", message -> message.payload().equals(new TicketEvent("ticket-1", "opened"))
                && failuresLeft.getAndDecrement() > 0);
            fixture.eventStore.inlet().send(ticketEvent("ticket-1", "opened"));
            fixture.eventStore.inlet().send(ticketEvent("ticket-2", "opened"));
            fixture.eventStore.inlet().send(ticketEvent("ticket-1", "closed"));

            fixture.eventStore.outlet().subscribe(subscriber);

            await().untilAsserted(() -> assertThat(subscriber.received()).hasSize(3));
            assertThat(subscriber.received())
                .filteredOn(message -> ((TicketEvent) message.payload()).ticketId().equals("ticket-1"))
                .extracting(message -> ((TicketEvent) message.payload()).change())
                .containsExactly("opened", "closed");
        }
    }
}
