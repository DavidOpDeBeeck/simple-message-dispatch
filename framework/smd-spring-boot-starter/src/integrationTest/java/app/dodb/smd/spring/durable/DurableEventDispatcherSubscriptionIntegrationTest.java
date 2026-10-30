package app.dodb.smd.spring.durable;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.spring.durable.DurableDeliveryTestFixture.PendingEvent;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.awaitRelease;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.durableDeliveryTestFixture;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.order;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.subscribe;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class DurableEventDispatcherSubscriptionIntegrationTest {

    private static final String DISPATCHER = "order-events";

    @Test
    void subscribe_afterEventsWereStored_receivesOnlyLaterEvents() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var audited = subscribe(dispatcher, "audit");
            var before = order("order-1", 1);
            var after = order("order-1", 2);
            fixture.publish(dispatcher.inlet(), before);
            scheduler.poll();

            // When
            var handled = subscribe(dispatcher, "billing");
            fixture.publish(dispatcher.inlet(), after);
            scheduler.poll();

            // Then
            assertThat(handled).containsExactly(after.payload());
            assertThat(audited).containsExactly(before.payload(), after.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void publish_beforeAnySubscriberRegistered_reachesNoOne() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var unheard = order("order-1", 1);

            // When
            fixture.publish(dispatcher.inlet(), unheard);

            // Then
            var handled = subscribe(dispatcher, "billing");
            scheduler.poll();
            assertThat(handled).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void subscribe_afterRestart_receivesEventsStoredWhileStopped() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var beforeRestart = fixture.dispatcher(DISPATCHER, scheduler);
            beforeRestart.subscribe(new EventSubscriberStub("billing", _ -> {
            })).close();
            var stored = order("order-1", 1);
            fixture.publish(beforeRestart.inlet(), stored);
            var afterRestart = fixture.dispatcher(DISPATCHER, scheduler);

            // When
            var handled = subscribe(afterRestart, "billing");
            scheduler.poll();

            // Then
            assertThat(handled).containsExactly(stored.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void subscribe_alreadyRegisteredWhileAnEventIsBeingStored_doesNotWaitForTheStore() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var transactions = fixture.bean(TransactionProvider.class);
            dispatcher.subscribe(new EventSubscriberStub("billing", _ -> {
            })).close();
            var stored = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var storing = executor.submit(() -> transactions.doInNewTransaction(() -> {
                dispatcher.send(order("order-1", 1));
                stored.countDown();
                awaitRelease(release);
            }));
            assertThat(stored.await(5, SECONDS)).as("store holds the shared dispatcher lock").isTrue();

            // When
            var subscribing = executor.submit(() -> subscribe(dispatcher, "billing"));

            // Then
            try {
                assertThat(subscribing).succeedsWithin(ofSeconds(5));
            } finally {
                release.countDown();
            }
            storing.get(5, SECONDS);
        }
    }

    @Test
    void subscribe_withOtherLaneCountThanRegistered_failsWithoutRegisteringTheSubscriber() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            subscribe(fixture.dispatcherWithLaneCount(DISPATCHER, scheduler, 16), "billing");
            var reconfigured = fixture.dispatcherWithLaneCount(DISPATCHER, scheduler, 32);

            // When / Then
            assertThatThrownBy(() -> subscribe(reconfigured, "audit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + DISPATCHER + "'")
                .hasMessageContaining("32 lanes")
                .hasMessageContaining("16 lanes")
                .hasMessageContaining("'audit'");
            assertThat(fixture.subscribers(DISPATCHER)).containsExactly("billing");
        }
    }

    @Test
    void close_duringDelivery_finishesTheBatchWithoutInterrupting() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var interrupted = new AtomicBoolean();
            var handled = new CopyOnWriteArrayList<Object>();
            var subscription = dispatcher.subscribe(new EventSubscriberStub("billing", message -> {
                entered.countDown();
                try {
                    assertThat(release.await(5, SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Handler interrupted while the subscription was closing", e);
                }
                handled.add(message.payload());
            }));
            var first = order("order-1", 1);
            fixture.publish(dispatcher.inlet(), first);

            var delivery = scheduler.pollAsync();
            try {
                assertThat(entered.await(5, SECONDS)).as("handler entered before closing").isTrue();

                // When
                subscription.close();
            } finally {
                release.countDown();
            }
            delivery.get(5, SECONDS);

            // Then
            assertThat(interrupted).isFalse();
            assertThat(handled).containsExactly(first.payload());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void close_subscription_stopsItsDeliveryButKeepsQueueingEventsForIt() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            var handled = new CopyOnWriteArrayList<Object>();
            var subscription = dispatcher.subscribe(new EventSubscriberStub("billing", message -> handled.add(message.payload())));
            var audited = subscribe(dispatcher, "audit");
            subscription.close();
            var placed = order("order-1", 1);

            // When
            fixture.publish(dispatcher.inlet(), placed);
            scheduler.poll();

            // Then
            assertThat(audited).containsExactly(placed.payload());
            assertThat(handled).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER))
                .extracting(PendingEvent::subscriber, PendingEvent::messageId)
                .containsExactly(tuple("billing", placed.messageId()));
        }
    }
}
