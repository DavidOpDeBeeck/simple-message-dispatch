package app.dodb.smd.jms;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.eventstore.durable.OutboxPosition;
import app.dodb.smd.eventstore.durable.TransportEvent;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.test.EventSubscriberStub;
import app.dodb.smd.test.NoOpTransactionProvider;
import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSRuntimeException;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static app.dodb.smd.jms.OrderEvent.orderEvent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
class JmsEventMediumIntegrationTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Container
    private static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.42.0")
        .withStartupTimeout(ofMinutes(3));

    private final JacksonEventSerializer serializer = new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());

    @Test
    void send_eventMessage_mapsWireFormat() throws Exception {
        // Given
        var queue = JmsDestination.queue("wire-format-" + UUID.randomUUID());
        var sent = orderEvent("order-1", 1, Map.of("traceparent", TRACEPARENT, "tenant", "acme"));
        try (var medium = new JmsEventMedium(config().build(), queue);
             var context = connectionFactory().createContext()) {

            // When
            medium.inlet().send(sent);

            // Then
            var received = context.createConsumer(context.createQueue(queue.name())).receive(10_000);
            var messageId = sent.messageId().value().toString();
            assertThat(received).isInstanceOf(BytesMessage.class);
            assertThat(received.getJMSType()).isEqualTo(OrderEvent.class.getName());
            assertThat(received.getJMSDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
            assertThat(received.getStringProperty("smd_wire_version")).isEqualTo("1");
            assertThat(received.getStringProperty("smd_message_id")).isEqualTo(messageId);
            assertThat(received.getStringProperty("_AMQ_DUPL_ID")).isEqualTo(messageId);
            assertThat(received.getStringProperty("JMSXGroupID")).isEqualTo("order-1");
            assertThat(received.getStringProperty("smd_subject_id")).isEqualTo("order-1");
            assertThat(received.getStringProperty("smd_timestamp")).isEqualTo("2026-10-22T10:15:30Z");
            assertThat(received.getStringProperty("traceparent")).isEqualTo(TRACEPARENT);
            assertThat(received.getStringProperty("smd_metadata")).isEqualTo(new String(serializer.serialize(sent).serializedMetadata(), UTF_8));
            assertThat(received.getBody(byte[].class)).isEqualTo(serializer.serialize(sent).serializedPayload());
        }
    }

    @Test
    void subscribe_topic_deliversEqualEventMessage() {
        // Given
        var topic = JmsDestination.topic("orders-" + UUID.randomUUID());
        var subscriberName = "orders-app-" + UUID.randomUUID();
        createSubscription(topic, subscriberName + ".projection." + topic.name());
        var received = new CopyOnWriteArrayList<EventMessage<?>>();
        try (var medium = new JmsEventMedium(config().subscriberName(subscriberName).build(), topic)) {
            medium.outlet().subscribe(new EventSubscriberStub("projection", received::add));
            var sent = orderEvent("order-1", 1, Map.of("tenant", "acme"));

            // When
            medium.inlet().send(sent);

            // Then
            await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(sent));
        }
    }

    @Test
    void subscribe_competingConsumers_keepsSubjectOrder() {
        // Given
        var topic = JmsDestination.topic("orders-" + UUID.randomUUID());
        var subscriberName = "orders-app-" + UUID.randomUUID();
        createSubscription(topic, subscriberName + ".projection." + topic.name());
        var received = new CopyOnWriteArrayList<OrderEvent>();
        var orders = List.of("order-1", "order-2", "order-3", "order-4");
        try (var medium = new JmsEventMedium(config().subscriberName(subscriberName).concurrency(3).build(), topic)) {
            medium.outlet().subscribe(new EventSubscriberStub("projection", message -> received.add((OrderEvent) message.payload())));

            // When
            for (int sequence = 1; sequence <= 25; sequence++) {
                for (var order : orders) {
                    medium.inlet().send(orderEvent(order, sequence));
                }
            }

            // Then
            await().atMost(ofSeconds(20)).untilAsserted(() -> assertThat(received).hasSize(100));
            var sequencesByOrder = received.stream().collect(groupingBy(OrderEvent::orderId, mapping(OrderEvent::sequence, toList())));
            assertThat(sequencesByOrder.values()).allSatisfy(sequences -> assertThat(sequences).isSorted().hasSize(25));
        }
    }

    @Test
    void subscribe_handlerFails_redeliversBeforeLaterEventsOfSubject() {
        // Given
        var topic = JmsDestination.topic("orders-" + UUID.randomUUID());
        var subscriberName = "orders-app-" + UUID.randomUUID();
        createSubscription(topic, subscriberName + ".projection." + topic.name());
        var failuresLeft = new AtomicInteger(2);
        var handled = new CopyOnWriteArrayList<Integer>();
        try (var medium = new JmsEventMedium(config().subscriberName(subscriberName).build(), topic)) {
            medium.outlet().subscribe(new EventSubscriberStub("projection", message -> {
                var event = (OrderEvent) message.payload();
                if (event.sequence() == 1 && failuresLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("Projection unavailable");
                }
                handled.add(event.sequence());
            }));

            // When
            for (int sequence = 1; sequence <= 3; sequence++) {
                medium.inlet().send(orderEvent("order-1", sequence));
            }

            // Then
            await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(handled).containsExactly(1, 2, 3));
        }
    }

    @Test
    void subscribe_unsupportedWireVersion_rollsBackUntilDeadLettered() throws Exception {
        // Given
        var queue = JmsDestination.queue("poison-" + UUID.randomUUID());
        var received = new CopyOnWriteArrayList<EventMessage<?>>();
        var messageId = UUID.randomUUID().toString();
        try (var source = new JmsEventSource(config().build(), queue);
             var context = connectionFactory().createContext()) {
            source.subscribe(new EventSubscriberStub("orders", received::add));
            var poison = context.createBytesMessage();
            poison.setStringProperty("smd_wire_version", "2");
            poison.setStringProperty("smd_message_id", messageId);

            // When
            context.createProducer().send(context.createQueue(queue.name()), poison);

            // Then
            var deadLetter = context.createConsumer(context.createQueue("DLQ"), "smd_message_id = '" + messageId + "'").receive(30_000);
            assertThat(deadLetter).isNotNull();
            assertThat(received).isEmpty();
        }
    }

    @Test
    void send_sameMessageTwice_isDeliveredOnce() {
        // Given
        var topic = JmsDestination.topic("orders-" + UUID.randomUUID());
        var subscriberName = "orders-app-" + UUID.randomUUID();
        createSubscription(topic, subscriberName + ".projection." + topic.name());
        var received = new CopyOnWriteArrayList<EventMessage<?>>();
        try (var medium = new JmsEventMedium(config().subscriberName(subscriberName).build(), topic)) {
            medium.outlet().subscribe(new EventSubscriberStub("projection", received::add));
            var resent = orderEvent("order-1", 1);
            var next = orderEvent("order-1", 2);

            // When
            medium.inlet().send(resent);
            medium.inlet().send(resent);
            medium.inlet().send(next);

            // Then
            await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactly(resent, next));
        }
    }

    @Test
    void send_brokerUnreachable_throwsDeliveryExceptionWithContext() {
        // Given
        var unreachable = new ActiveMQConnectionFactory("tcp://localhost:1?initialConnectAttempts=1&reconnectAttempts=0");
        var queue = JmsDestination.queue("orders");
        var sent = orderEvent("order-1", 1);
        try (var sink = new JmsEventSink(config().connectionFactory(unreachable).build(), queue)) {

            // When / Then
            assertThatThrownBy(() -> sink.send(sent))
                .isInstanceOf(JmsDeliveryException.class)
                .hasMessage("Failed to send event to JMS queue 'orders': messageId=" + sent.messageId().value() + ", eventType=" + OrderEvent.class.getName())
                .hasCauseInstanceOf(JMSRuntimeException.class);
        }
    }

    @Test
    void subscribe_secondProcessingGroupOnQueue_fails() {
        // Given
        var queue = JmsDestination.queue("orders-" + UUID.randomUUID());
        try (var source = new JmsEventSource(config().build(), queue)) {
            source.subscribe(new EventSubscriberStub("first", _ -> {
            }));

            // When / Then
            assertThatThrownBy(() -> source.subscribe(new EventSubscriberStub("second", _ -> {
            })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JMS queue '" + queue.name() + "' already feeds 'first'; a queue has no copy per subscriber, so it cannot also feed 'second'");
        }
    }

    @Test
    void subscribe_transportReceiver_receivesEventWithOutboxPosition() {
        // Given
        var topic = JmsDestination.topic("orders-" + UUID.randomUUID());
        var subscriberName = "billing-app-" + UUID.randomUUID();
        createSubscription(topic, subscriberName + ".billing." + topic.name());
        var received = new CopyOnWriteArrayList<TransportEvent>();
        var serializedEvent = serializer.serialize(orderEvent("order-1", 1));
        var sent = new TransportEvent(serializedEvent, Optional.of(new OutboxPosition("orders.events", 7)));
        try (var medium = new JmsEventMedium(config().subscriberName(subscriberName).build(), topic)) {
            medium.outlet().subscribe("billing", received::add);

            // When
            medium.inlet().send(sent);

            // Then
            await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(received).singleElement().satisfies(event -> {
                assertThat(event.position()).contains(new OutboxPosition("orders.events", 7));
                assertThat(event.event().messageId()).isEqualTo(serializedEvent.messageId());
                assertThat(event.event().subjectId()).contains("order-1");
                assertThat(event.event().eventType()).isEqualTo(serializedEvent.eventType());
                assertThat(event.event().serializedPayload()).isEqualTo(serializedEvent.serializedPayload());
                assertThat(event.event().serializedMetadata()).isEqualTo(serializedEvent.serializedMetadata());
                assertThat(event.event().createdAt()).isEqualTo(serializedEvent.createdAt());
            }));
        }
    }

    @Test
    void subscribe_transportReceiverGetsSubjectWithoutPosition_rollsBackUntilDeadLettered() {
        // Given
        var queue = JmsDestination.queue("unpositioned-" + UUID.randomUUID());
        var received = new CopyOnWriteArrayList<TransportEvent>();
        var sent = orderEvent("order-1", 1);
        try (var medium = new JmsEventMedium(config().build(), queue);
             var context = connectionFactory().createContext()) {
            medium.outlet().subscribe("billing", received::add);

            // When
            medium.inlet().send(sent);

            // Then
            var deadLetter = context.createConsumer(context.createQueue("DLQ"), "smd_message_id = '" + sent.messageId().value() + "'").receive(30_000);
            assertThat(deadLetter).isNotNull();
            assertThat(received).isEmpty();
        }
    }

    @Test
    void subscribe_sameSubscriberOnTwoTopics_opensSeparateSubscriptions() {
        // Given
        var orders = JmsDestination.topic("orders-" + UUID.randomUUID());
        var payments = JmsDestination.topic("payments-" + UUID.randomUUID());
        var subscriberName = "billing-app-" + UUID.randomUUID();
        createSubscription(orders, subscriberName + ".billing." + orders.name());
        createSubscription(payments, subscriberName + ".billing." + payments.name());
        var received = new CopyOnWriteArrayList<EventMessage<?>>();
        try (var ordersMedium = new JmsEventMedium(config().subscriberName(subscriberName).build(), orders);
             var paymentsMedium = new JmsEventMedium(config().subscriberName(subscriberName).build(), payments)) {
            ordersMedium.outlet().subscribe(new EventSubscriberStub("billing", received::add));
            paymentsMedium.outlet().subscribe(new EventSubscriberStub("billing", received::add));
            var order = orderEvent("order-1", 1);
            var payment = orderEvent("order-2", 1);

            // When
            ordersMedium.inlet().send(order);
            paymentsMedium.inlet().send(payment);

            // Then
            await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(received).containsExactlyInAnyOrder(order, payment));
        }
    }

    private JmsConfig.Builder config() {
        return JmsConfig.withDefaults()
            .connectionFactory(connectionFactory())
            .eventSerializer(serializer)
            .transactionProvider(new NoOpTransactionProvider())
            .receiveTimeout(ofMillis(200));
    }

    private static void createSubscription(JmsDestination topic, String subscriptionName) {
        try (var context = connectionFactory().createContext()) {
            context.createSharedDurableConsumer(context.createTopic(topic.name()), subscriptionName).close();
        }
    }

    private static ConnectionFactory connectionFactory() {
        return new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword());
    }
}
