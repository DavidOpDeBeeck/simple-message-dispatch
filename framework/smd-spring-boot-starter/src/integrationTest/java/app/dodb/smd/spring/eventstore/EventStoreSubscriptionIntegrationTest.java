package app.dodb.smd.spring.eventstore;

import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.spring.eventstore.EventStoreTestFixture.SequencedEvent;
import app.dodb.smd.spring.eventstore.processing.TestEventWithSubjectId;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventStoreSubscriptionIntegrationTest {

    @Test
    void close_withDuplicateSubscriber_cancelsOnlyOwnRegistrationIdempotently() {
        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start();
             var eventStore = fixture.createEventStore()) {
            var processingGroup = "duplicate-subscription";
            var received = new CopyOnWriteArrayList<String>();
            var subscriber = new EventSubscriberStub(processingGroup, message ->
                received.add(message.metadata().properties().get("sequenceNumber")));

            try (var first = eventStore.subscribe(subscriber);
                 var second = eventStore.subscribe(subscriber)) {
                first.close();
                fixture.storeEvents(new SequencedEvent<>(1L, new TestEventWithSubjectId("subscription")));

                await().atMost(ofSeconds(5)).untilAsserted(() -> {
                    assertThat(received).containsExactly("1");
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                });

                second.close();
                fixture.storeEvents(new SequencedEvent<>(2L, new TestEventWithSubjectId("subscription")));

                await().during(ofMillis(500)).atMost(ofSeconds(5)).untilAsserted(() -> {
                    assertThat(received).containsExactly("1");
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                });
            }
        }
    }

    @Test
    void close_duringDelivery_allowsCommitWithoutInterruptingAndStopsLaterPolling() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var received = new CopyOnWriteArrayList<String>();
        var processingGroup = "in-flight-subscription";

        try (var fixture = eventStoreTestFixture().properties(SCHEDULING_DISABLED).start();
             var eventStore = fixture.createEventStore()) {
            fixture.storeEvents(new SequencedEvent<>(1L, new TestEventWithSubjectId("subscription")));
            var subscriber = new EventSubscriberStub(processingGroup, message -> {
                entered.countDown();
                try {
                    assertThat(release.await(5, SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Handler interrupted while subscription was closing", e);
                }
                received.add(message.metadata().properties().get("sequenceNumber"));
            });

            try (var subscription = eventStore.subscribe(subscriber)) {
                try {
                    assertThat(entered.await(5, SECONDS)).isTrue();

                    subscription.close();
                } finally {
                    release.countDown();
                }

                await().atMost(ofSeconds(5)).untilAsserted(() -> {
                    assertThat(interrupted).isFalse();
                    assertThat(received).containsExactly("1");
                    assertThat(fixture.tokenState(processingGroup)
                        .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                });
                fixture.storeEvents(new SequencedEvent<>(2L, new TestEventWithSubjectId("subscription")));

                try (var _ = eventStore.subscribe(new EventSubscriberStub("other-subscription", _ -> {
                }))) {
                    await().atMost(ofSeconds(5)).untilAsserted(() ->
                        assertThat(fixture.tokenState("other-subscription")
                            .flatMap(TokenState::lastProcessedSequenceNumber)).contains(2L));
                    await().during(ofMillis(500)).atMost(ofSeconds(5)).untilAsserted(() -> {
                        assertThat(received).containsExactly("1");
                        assertThat(fixture.tokenState(processingGroup)
                            .flatMap(TokenState::lastProcessedSequenceNumber)).contains(1L);
                    });
                }
            }
        }
    }
}
