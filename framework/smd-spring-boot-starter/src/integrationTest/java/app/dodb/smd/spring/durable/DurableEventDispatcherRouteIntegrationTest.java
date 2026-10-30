package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.delivery.EventRoute;
import app.dodb.smd.durable.storage.DispatcherStorageException;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer.EventSerializationException;
import app.dodb.smd.spring.ManualPollingScheduler;
import app.dodb.smd.spring.durable.DurableDeliveryTestFixture.FailedDelivery;
import app.dodb.smd.spring.durable.DurableDeliveryTestFixture.PendingEvent;
import org.junit.jupiter.api.Test;

import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.durableDeliveryTestFixture;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.metadata;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.note;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.order;
import static app.dodb.smd.spring.durable.DurableDeliveryTestFixture.subscribe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class DurableEventDispatcherRouteIntegrationTest {

    private static final String DISPATCHER = "order-events";

    private final LoopbackMedium broker = new LoopbackMedium();

    @Test
    void poll_routedToBroker_sendsEventsInOrderPerSubject() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            startRoute(new EventRoute("orders.relay", dispatcher.outlet(), broker.inlet()));
            var placed = order("order-1", 1);
            var noted = note("hello");
            var paid = order("order-1", 2);
            fixture.publish(dispatcher.inlet(), placed, noted, paid);

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(broker.sent()).extracting(EventMessage::messageId)
                .hasSize(3)
                .containsSubsequence(placed.messageId(), paid.messageId())
                .contains(noted.messageId());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void poll_whenBrokerSendFails_retriesWithoutReorderingSubject() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            startRoute(new EventRoute("orders.relay", dispatcher.outlet(), broker.inlet()));
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);
            broker.failNextSends(1);
            fixture.publish(dispatcher.inlet(), placed, paid);

            // When
            scheduler.poll();

            // Then
            assertThat(broker.sent()).isEmpty();
            assertThat(fixture.pendingEvents(DISPATCHER)).extracting(PendingEvent::messageId).containsExactly(placed.messageId(), paid.messageId());
            assertThat(fixture.failedDeliveries(DISPATCHER))
                .extracting(FailedDelivery::subject, FailedDelivery::attempts)
                .containsExactly(tuple("order-1", 1));

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(broker.sent()).extracting(EventMessage::messageId).containsExactly(placed.messageId(), paid.messageId());
            assertThat(fixture.pendingEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void receive_redeliveredMessage_deliversItOnce() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            startRoute(new EventRoute("orders.intake", broker.outlet(), dispatcher.inlet()));
            var handled = subscribe(dispatcher, "billing");
            var placed = order("order-1", 1);
            var paid = order("order-1", 2);

            // When
            broker.deliver(placed);
            broker.deliver(placed);
            broker.deliver(paid);

            // Then
            assertThat(fixture.queuedEvents(DISPATCHER)).containsExactly(placed.messageId(), paid.messageId());

            // When
            fixture.pollUntilIdle(scheduler);

            // Then
            assertThat(handled).containsExactly(placed.payload(), paid.payload());
        }
    }

    @Test
    void receive_unknownEventType_throwsAndStoresNothing() {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            startRoute(new EventRoute("orders.intake", broker.outlet(), dispatcher.inlet()));
            var unknown = EventMessage.from(new UnregisteredEvent("draft"), metadata());

            // When / Then
            assertThatThrownBy(() -> broker.deliver(unknown)).isInstanceOf(EventSerializationException.class);

            assertThat(fixture.queuedEvents(DISPATCHER)).isEmpty();
        }
    }

    @Test
    void receive_whenStoringFails_storesNothingAndAcceptsRedelivery() throws Exception {
        // Given
        try (var fixture = durableDeliveryTestFixture();
             var scheduler = new ManualPollingScheduler()) {
            var dispatcher = fixture.dispatcher(DISPATCHER, scheduler);
            startRoute(new EventRoute("orders.intake", broker.outlet(), dispatcher.inlet()));
            var handled = subscribe(dispatcher, "billing");
            var placed = order("order-1", 1);
            fixture.execute("""
                CREATE FUNCTION reject_storing() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'Storing unavailable';
                END
                $$ LANGUAGE plpgsql
                """);
            fixture.execute("CREATE TRIGGER reject_storing BEFORE INSERT ON smd_dispatcher_queue FOR EACH ROW EXECUTE FUNCTION reject_storing()");

            // When / Then
            assertThatThrownBy(() -> broker.deliver(placed)).isInstanceOf(DispatcherStorageException.class);

            assertThat(fixture.queuedEvents(DISPATCHER)).isEmpty();

            // When
            fixture.execute("DROP TRIGGER reject_storing ON smd_dispatcher_queue");
            broker.deliver(placed);
            scheduler.poll();

            // Then
            assertThat(handled).containsExactly(placed.payload());
        }
    }

    private static void startRoute(EventRoute route) {
        EventBusSpec.withDefaults().routes(route).create();
    }

    record UnregisteredEvent(String text) implements Event {
    }
}
