package app.dodb.smd.spring.durable;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.Test;

import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.durableDeliveryTestFixture;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableEventDispatcherSendingIntegrationTest {

    private static final String DISPATCHER = "order-events";

    @Test
    void send_withinTransaction_storesEventWhenTransactionCommits() {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            dispatcher.subscribe(new EventSubscriberStub("billing", _ -> {
            })).close();
            var message = order("order-1", 1);

            // When
            fixture.bean(TransactionProvider.class).doInTransaction(() -> {
                dispatcher.send(message);

                assertThat(fixture.queuedEvents(DISPATCHER)).isEmpty();
            });

            // Then
            assertThat(fixture.queuedEvents(DISPATCHER)).containsExactly(message.messageId());
        }
    }

    @Test
    void send_whenLaterDeferredWorkFails_rollsBackStoredEventAndPreservesFailure() {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            dispatcher.subscribe(new EventSubscriberStub("billing", _ -> {
            })).close();
            var transactions = fixture.bean(TransactionProvider.class);
            var failure = new IllegalStateException("Deferred work failed after storing the event");
            var message = order("order-1", 1);

            // When / Then
            assertThatThrownBy(() -> transactions.doInTransaction(() -> {
                dispatcher.send(message);
                transactions.defer(() -> {
                    assertThat(fixture.queuedEvents(DISPATCHER)).containsExactly(message.messageId());
                    throw failure;
                });
            })).isSameAs(failure);

            assertThat(fixture.queuedEvents(DISPATCHER)).isEmpty();
        }
    }
}
