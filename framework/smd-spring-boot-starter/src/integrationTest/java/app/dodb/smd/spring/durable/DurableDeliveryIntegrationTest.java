package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.eventstore.durable.DurableDelivery;
import app.dodb.smd.eventstore.durable.DurableDeliveryConfig;
import app.dodb.smd.eventstore.durable.OutboxPosition;
import app.dodb.smd.eventstore.durable.TransportEvent;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.serialization.StaticEventTypeResolver;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import app.dodb.smd.test.EventSubscriberStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.eventstore.RetryBackoffStrategy.fixed;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

class DurableDeliveryIntegrationTest {

    private static ConfigurableApplicationContext context;

    private final Map<String, Class<? extends Event>> eventTypes = new ConcurrentHashMap<>(Map.of(
        "order.v1", OrderEvent.class,
        "note.v1", NoteEvent.class));
    private final EventSerializer serializer = new JacksonEventSerializer(
        JsonMapper.builder().addModule(new SMDJacksonModule()).build(), new StaticEventTypeResolver(eventTypes));
    private final String outboxName = "orders-" + UUID.randomUUID();
    private final String inboxName = "billing-" + UUID.randomUUID();
    private final LoopbackTransport transport = new LoopbackTransport();
    private DurableDelivery delivery;

    @BeforeAll
    static void startContext() {
        context = new SpringApplicationBuilder(DurableDeliveryTestConfiguration.class)
            .web(NONE)
            .run(
                "--spring.datasource.url=jdbc:tc:postgresql:18-alpine:///smd?TC_INITSCRIPT=db/smd/durable-delivery-schema.sql",
                "--spring.datasource.username=test_user",
                "--spring.datasource.password=test_password",
                "--smd.durable-delivery.enabled=true"
            );
    }

    @AfterAll
    static void stopContext() {
        context.close();
    }

    @BeforeEach
    void createDelivery() {
        delivery = new DurableDelivery(DurableDeliveryConfig.withoutDefaults()
            .connectionProvider(context.getBean(ConnectionProvider.class))
            .transactionProvider(transactions())
            .eventSerializer(serializer)
            .scheduler(newScheduledThreadPool(4))
            .pollingDelay(ofMillis(20))
            .stallWarningAfter(ofHours(1))
            .retryBackoffStrategy(fixed(ofMillis(50)))
            .build());
    }

    @AfterEach
    void closeDelivery() {
        delivery.close();
    }

    @Test
    void outbox_committedTransaction_sendsEventsWithPositionPerSubject() {
        // Given
        var outbox = delivery.outbox(outboxName, transport);

        // When
        publish(outbox, order("order-1", 1), order("order-2", 1), note("hello"), order("order-1", 2));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(transport.sent()).hasSize(4));
        assertThat(transport.sent()).extracting(event -> event.event().subjectId().orElse(null), TransportEvent::position).containsExactly(
            tuple("order-1", Optional.of(new OutboxPosition(outboxName, 1))),
            tuple("order-2", Optional.of(new OutboxPosition(outboxName, 1))),
            tuple(null, Optional.empty()),
            tuple("order-1", Optional.of(new OutboxPosition(outboxName, 2))));
    }

    @Test
    void outbox_rolledBackTransaction_sendsNothing() {
        // Given
        var outbox = delivery.outbox(outboxName, transport);
        var failure = new IllegalStateException("Command failed after publishing");
        var committed = order("order-1", 2);

        // When
        assertThatThrownBy(() -> transactions().doInTransaction(() -> {
            outbox.send(order("order-1", 1));
            throw failure;
        })).isSameAs(failure);
        publish(outbox, committed);

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(transport.sent()).hasSize(1));
        assertThat(transport.sent().getFirst().event().messageId()).isEqualTo(committed.messageId());
        assertThat(transport.sent().getFirst().position()).contains(new OutboxPosition(outboxName, 1));
    }

    @Test
    void outbox_transportFails_retriesWithoutReorderingSubject() {
        // Given
        var outbox = delivery.outbox(outboxName, transport);
        var first = order("order-1", 1);
        var second = order("order-1", 2);
        transport.failNextSends(2);

        // When
        publish(outbox, first, second);

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(transport.sent()).hasSize(2));
        assertThat(transport.sent()).extracting(event -> event.event().messageId()).containsExactly(first.messageId(), second.messageId());
        assertThat(outboxAttempts(first)).isEqualTo(2);
    }

    @Test
    void outboxToInbox_twoProcessingGroups_deliverEachEventOnceInOrderWithMetadata() {
        // Given
        var outbox = delivery.outbox(outboxName, transport);
        var inbox = delivery.inbox(inboxName, transport);
        var billing = new CopyOnWriteArrayList<EventMessage<?>>();
        var audit = new CopyOnWriteArrayList<EventMessage<?>>();
        inbox.subscribe(new EventSubscriberStub("billing", billing::add));
        inbox.subscribe(new EventSubscriberStub("audit", audit::add));
        var placed = order("order-1", 1);
        var paid = order("order-1", 2);

        // When
        publish(outbox, placed, paid);

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            assertThat(billing).extracting(message -> (Object) message.payload()).containsExactly(placed.payload(), paid.payload());
            assertThat(audit).extracting(message -> (Object) message.payload()).containsExactly(placed.payload(), paid.payload());
        });
        assertThat(billing.getFirst().messageId()).isEqualTo(placed.messageId());
        assertThat(billing.getFirst().metadata().properties())
            .containsEntry("tenant", "acme")
            .containsEntry("subjectId", "order-1")
            .containsEntry("errorCount", "0")
            .containsKey("processingId");
    }

    @Test
    void inbox_positionsArriveOutOfOrder_deliversInPositionOrder() {
        // Given
        var handled = subscribe(delivery.inbox(inboxName, transport), "billing");

        // When
        transport.deliver(positioned("order-1", 1));
        transport.deliver(positioned("order-1", 3));
        transport.deliver(positioned("order-1", 2));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-1", 2), new OrderEvent("order-1", 3)));
    }

    @Test
    void inbox_missingPosition_holdsOnlyThatSubject() {
        // Given
        var handled = subscribe(delivery.inbox(inboxName, transport), "billing");
        transport.deliver(positioned("order-1", 1));
        transport.deliver(positioned("order-1", 3));
        transport.deliver(positioned("order-2", 1));
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-2", 1)));

        // When
        transport.deliver(positioned("order-1", 2));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-2", 1), new OrderEvent("order-1", 2), new OrderEvent("order-1", 3)));
    }

    @Test
    void inbox_duplicateMessage_deliversOnce() {
        // Given
        var handled = subscribe(delivery.inbox(inboxName, transport), "billing");
        var first = positioned("order-1", 1);

        // When
        transport.deliver(first);
        transport.deliver(first);
        transport.deliver(positioned("order-1", 2));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-1", 2)));
    }

    @Test
    void inbox_handlerFails_retriesSubjectInOrderWhileOtherSubjectsContinue() {
        // Given
        var inbox = delivery.inbox(inboxName, transport);
        var failuresLeft = new AtomicInteger(2);
        var handled = new CopyOnWriteArrayList<Object>();
        inbox.subscribe(new EventSubscriberStub("billing", message -> {
            if (message.payload().equals(new OrderEvent("order-1", 1)) && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("Billing unavailable");
            }
            handled.add(message.payload());
        }));

        // When
        transport.deliver(positioned("order-1", 1));
        transport.deliver(positioned("order-1", 2));
        transport.deliver(positioned("order-2", 1));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-2", 1), new OrderEvent("order-1", 1), new OrderEvent("order-1", 2)));
    }

    @Test
    void inbox_subjectlessEventFails_otherSubjectlessEventsContinue() {
        // Given
        var inbox = delivery.inbox(inboxName, transport);
        var failuresLeft = new AtomicInteger(2);
        var handled = new CopyOnWriteArrayList<Object>();
        inbox.subscribe(new EventSubscriberStub("billing", message -> {
            if (message.payload().equals(new NoteEvent("first")) && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("Billing unavailable");
            }
            handled.add(message.payload());
        }));

        // When
        transport.deliver(unpositioned(note("first")));
        transport.deliver(unpositioned(note("second")));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(new NoteEvent("second"), new NoteEvent("first")));
    }

    @Test
    void inbox_unknownEventType_waitsUntilTheTypeResolves() {
        // Given
        var handled = subscribe(delivery.inbox(inboxName, transport), "billing");
        var unknown = new TransportEvent(withEventType(serializer.serialize(order("order-1", 1)), "order.v2"), Optional.of(new OutboxPosition("orders", 1)));
        transport.deliver(unknown);
        transport.deliver(positioned("order-1", 2));
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(pendingAttempts(unknown)).isGreaterThan(1));
        assertThat(handled).isEmpty();

        // When
        eventTypes.put("order.v2", OrderEvent.class);

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-1", 2)));
    }

    @Test
    void inbox_groupSubscribesLater_receivesStoredEvents() {
        // Given
        var inbox = delivery.inbox(inboxName, transport);
        transport.deliver(positioned("order-1", 1));
        transport.deliver(unpositioned(note("hello")));
        transport.deliver(positioned("order-1", 2));

        // When
        var handled = subscribe(inbox, "billing");

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new NoteEvent("hello"), new OrderEvent("order-1", 2)));
    }

    @Test
    void inbox_twoSources_deliversBothToOneGroup() {
        // Given
        var payments = new LoopbackTransport();
        var handled = subscribe(delivery.inbox(inboxName, transport, payments), "billing");

        // When
        transport.deliver(positioned("order-1", 1, "orders.events"));
        payments.deliver(positioned("order-1", 1, "payments.events"));
        payments.deliver(positioned("order-1", 2, "payments.events"));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-1", 1), new OrderEvent("order-1", 2)));
    }

    @Test
    void inbox_secondMessageAtSamePosition_isDropped() {
        // Given
        var handled = subscribe(delivery.inbox(inboxName, transport), "billing");

        // When
        transport.deliver(positioned("order-1", 1));
        transport.deliver(new TransportEvent(serializer.serialize(order("order-1", 99)), Optional.of(new OutboxPosition("orders", 1))));
        transport.deliver(positioned("order-1", 2));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 1), new OrderEvent("order-1", 2)));
    }

    @Test
    void inbox_positionArrivesBehindCursor_isNotDelivered() {
        // Given
        var handled = subscribe(delivery.inbox(inboxName, transport), "billing");
        transport.deliver(positioned("order-1", 2));
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(new OrderEvent("order-1", 2)));

        // When
        transport.deliver(positioned("order-1", 1));
        transport.deliver(positioned("order-1", 3));

        // Then
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(
            new OrderEvent("order-1", 2), new OrderEvent("order-1", 3)));
    }

    @Test
    void inbox_sameProcessingGroupTwice_fails() {
        // Given
        var inbox = delivery.inbox(inboxName, transport);
        inbox.subscribe(new EventSubscriberStub("billing", _ -> {
        }));

        // When / Then
        assertThatThrownBy(() -> inbox.subscribe(new EventSubscriberStub("billing", _ -> {
        })))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Processing group 'billing' is already subscribed to inbox '" + inboxName + "'");
    }

    @Test
    void outbox_sameNameTwice_fails() {
        // Given
        delivery.outbox(outboxName, transport);

        // When / Then
        assertThatThrownBy(() -> delivery.outbox(outboxName, transport))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Outbox '" + outboxName + "' is already declared");
    }

    private static TransactionProvider transactions() {
        return context.getBean(TransactionProvider.class);
    }

    private static void publish(EventSink outbox, EventMessage<?>... messages) {
        transactions().doInTransaction(() -> {
            for (var message : messages) {
                outbox.send(message);
            }
        });
    }

    private static List<Object> subscribe(EventSource inbox, String processingGroup) {
        var handled = new CopyOnWriteArrayList<Object>();
        inbox.subscribe(new EventSubscriberStub(processingGroup, message -> handled.add(message.payload())));
        return handled;
    }

    private TransportEvent positioned(String orderId, int sequence) {
        return positioned(orderId, sequence, "orders");
    }

    private TransportEvent positioned(String orderId, int sequence, String outbox) {
        return new TransportEvent(serializer.serialize(order(orderId, sequence)), Optional.of(new OutboxPosition(outbox, sequence)));
    }

    private TransportEvent unpositioned(EventMessage<?> message) {
        return new TransportEvent(serializer.serialize(message), Optional.empty());
    }

    private static EventMessage<OrderEvent> order(String orderId, int sequence) {
        return EventMessage.from(new OrderEvent(orderId, sequence), metadata());
    }

    private static EventMessage<NoteEvent> note(String text) {
        return EventMessage.from(new NoteEvent(text), metadata());
    }

    private static Metadata metadata() {
        return new Metadata(null, Instant.parse("2026-10-22T10:15:30Z"), null, Map.of("tenant", "acme"));
    }

    private static SerializedEvent withEventType(SerializedEvent event, String eventType) {
        return new SerializedEvent(event.messageId(), null, event.subjectId(), eventType, event.serializedPayload(), event.serializedMetadata(), event.createdAt());
    }

    private static int outboxAttempts(EventMessage<?> message) {
        return queryInt("SELECT attempts FROM smd_outbox WHERE message_id = ?", message.messageId().value());
    }

    private int pendingAttempts(TransportEvent event) {
        return queryInt("""
            SELECT p.attempts FROM smd_inbox_pending p JOIN smd_inbox i ON i.id = p.id
            WHERE i.inbox = ? AND i.message_id = ?
            """, inboxName, event.event().messageId().value());
    }

    private static int queryInt(String sql, Object... parameters) {
        try (var connection = context.getBean(DataSource.class).getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }
}
