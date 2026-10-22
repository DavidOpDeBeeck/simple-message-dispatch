package app.dodb.smd.spike.activemq;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Message;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Settles broker behavior the ActiveMQ outbox design relies on. Not part of the SMD build.
@Testcontainers
class ArtemisOutboxSpikeTest {

    private static final String GROUP_ID = "JMSXGroupID";
    private static final String DUPLICATE_ID = "_AMQ_DUPL_ID";
    private static final Duration REDELIVERY_DELAY = Duration.ofSeconds(2);

    @Container
    private static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.42.0")
        .withEnv("JDK_JAVA_OPTIONS", "-Dbrokerconfig.addressSettings.delayed.redeliveryDelay=" + REDELIVERY_DELAY.toMillis());

    @Test
    void sharedDurableSubscription_competingConsumers_keepsPerGroupOrder() throws Exception {
        var connectionFactory = connectionFactory();
        var received = new ConcurrentLinkedQueue<Received>();
        var consumers = new ArrayList<JMSContext>();
        for (int consumer = 0; consumer < 3; consumer++) {
            var context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
            var consumerIndex = consumer;
            context.createSharedDurableConsumer(context.createTopic("tickets"), "billing")
                .setMessageListener(message -> received.add(Received.from(message, consumerIndex)));
            consumers.add(context);
        }
        var groups = List.of("ticket-1", "ticket-2", "ticket-3", "ticket-4", "ticket-5", "ticket-6");
        int messagesPerGroup = 40;

        try (var producer = connectionFactory.createContext()) {
            var topic = producer.createTopic("tickets");
            for (int sequence = 1; sequence <= messagesPerGroup; sequence++) {
                for (var group : groups) {
                    var message = producer.createTextMessage(group + ":" + sequence);
                    message.setStringProperty(GROUP_ID, group);
                    message.setIntProperty("sequence", sequence);
                    producer.createProducer().send(topic, message);
                }
            }
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> received.size() == groups.size() * messagesPerGroup);
        consumers.forEach(JMSContext::close);

        Map<String, List<Integer>> sequencesByGroup = received.stream()
            .collect(groupingBy(Received::group, mapping(Received::sequence, toList())));
        Map<String, Set<Integer>> consumersByGroup = received.stream()
            .collect(groupingBy(Received::group, mapping(Received::consumer, toSet())));
        System.out.println("Consumers by group: " + consumersByGroup);
        assertThat(sequencesByGroup.values()).allSatisfy(sequences -> assertThat(sequences).isSorted().hasSize(messagesPerGroup));
        assertThat(consumersByGroup.values()).allSatisfy(owners -> assertThat(owners).hasSize(1));
        assertThat(received.stream().map(Received::consumer).distinct().count()).isGreaterThan(1);
    }

    @Test
    void duplicateId_resentFromNewConnection_isDroppedByBroker() throws Exception {
        var connectionFactory = connectionFactory();
        var messageId = UUID.randomUUID().toString();

        sendToQueue(connectionFactory, "relayDedup", "first", messageId);
        sendToQueue(connectionFactory, "relayDedup", "resent after relay crash", messageId);
        sendToQueue(connectionFactory, "relayDedup", "other event", UUID.randomUUID().toString());

        assertThat(drainQueue(connectionFactory, "relayDedup")).containsExactly("first", "other event");
    }

    @Test
    void duplicateId_resentToTopic_isDroppedForDurableSubscription() throws Exception {
        var connectionFactory = connectionFactory();
        try (var context = connectionFactory.createContext()) {
            context.createSharedDurableConsumer(context.createTopic("relayTopic"), "inventory").close();
        }
        var messageId = UUID.randomUUID().toString();

        for (var body : List.of("first", "resent after relay crash")) {
            try (var context = connectionFactory.createContext()) {
                var message = context.createTextMessage(body);
                message.setStringProperty(DUPLICATE_ID, messageId);
                context.createProducer().send(context.createTopic("relayTopic"), message);
            }
        }

        var bodies = new ArrayList<String>();
        try (var context = connectionFactory.createContext();
             var consumer = context.createSharedDurableConsumer(context.createTopic("relayTopic"), "inventory")) {
            Message message;
            while ((message = consumer.receive(1_000)) != null) {
                bodies.add(message.getBody(String.class));
            }
        }
        assertThat(bodies).containsExactly("first");
    }

    @Test
    void rollback_withoutRedeliveryDelay_keepsGroupOrder() throws Exception {
        var committed = rollBackFirstMessageWhileLaterMessagesArrive(connectionFactory(), "ordered");

        assertThat(committed).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void rollback_withRedeliveryDelay_letsLaterGroupMessagesOvertake() throws Exception {
        var committed = rollBackFirstMessageWhileLaterMessagesArrive(connectionFactory(), "delayed");

        assertThat(committed).containsExactly(2, 3, 4, 5, 1);
    }

    private static List<Integer> rollBackFirstMessageWhileLaterMessagesArrive(ConnectionFactory connectionFactory, String queueName) throws Exception {
        var committed = new ArrayList<Integer>();
        try (var context = connectionFactory.createContext(JMSContext.SESSION_TRANSACTED);
             var consumer = context.createConsumer(context.createQueue(queueName))) {
            sendGroupMessage(connectionFactory, queueName, 1);
            var first = consumer.receive(5_000);
            assertThat(first.getIntProperty("sequence")).isEqualTo(1);
            context.rollback();
            for (int sequence = 2; sequence <= 5; sequence++) {
                sendGroupMessage(connectionFactory, queueName, sequence);
            }

            while (committed.size() < 5) {
                var message = consumer.receive(REDELIVERY_DELAY.multipliedBy(3).toMillis());
                assertThat(message).as("message within redelivery window").isNotNull();
                committed.add(message.getIntProperty("sequence"));
                context.commit();
            }
        }
        return committed;
    }

    private static void sendGroupMessage(ConnectionFactory connectionFactory, String queueName, int sequence) throws Exception {
        try (var context = connectionFactory.createContext()) {
            var message = context.createTextMessage("ticket-1:" + sequence);
            message.setStringProperty(GROUP_ID, "ticket-1");
            message.setIntProperty("sequence", sequence);
            context.createProducer().send(context.createQueue(queueName), message);
        }
    }

    private static void sendToQueue(ConnectionFactory connectionFactory, String queueName, String body, String duplicateId) throws Exception {
        try (var context = connectionFactory.createContext()) {
            var message = context.createTextMessage(body);
            message.setStringProperty(DUPLICATE_ID, duplicateId);
            context.createProducer().send(context.createQueue(queueName), message);
        }
    }

    private static List<String> drainQueue(ConnectionFactory connectionFactory, String queueName) throws Exception {
        var bodies = new ArrayList<String>();
        try (var context = connectionFactory.createContext();
             var consumer = context.createConsumer(context.createQueue(queueName))) {
            Message message;
            while ((message = consumer.receive(1_000)) != null) {
                bodies.add(message.getBody(String.class));
            }
        }
        return bodies;
    }

    private static ConnectionFactory connectionFactory() {
        return new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword());
    }

    private record Received(String group, int sequence, int consumer) {

        static Received from(Message message, int consumer) {
            try {
                return new Received(message.getStringProperty(GROUP_ID), message.getIntProperty("sequence"), consumer);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
