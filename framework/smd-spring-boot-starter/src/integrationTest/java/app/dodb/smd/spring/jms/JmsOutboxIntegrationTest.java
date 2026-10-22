package app.dodb.smd.spring.jms;

import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.spring.jms.outbox.BillingHandler;
import app.dodb.smd.spring.jms.outbox.JmsOutboxTestConfiguration;
import app.dodb.smd.spring.jms.outbox.TicketChangedEvent;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

@Testcontainers
class JmsOutboxIntegrationTest {

    @Container
    private static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.42.0")
        .withStartupTimeout(ofMinutes(3));

    @Test
    void publish_committedTransaction_reachesInboxGroupThroughBroker() {
        try (var context = start()) {
            // Given
            var events = context.getBean(EventPublisher.class);
            var billing = context.getBean(BillingHandler.class);

            // When
            events.publish(new TicketChangedEvent("ticket-1", "opened"));
            events.publish(new TicketChangedEvent("ticket-1", "closed"));

            // Then
            await().atMost(ofSeconds(20)).untilAsserted(() -> assertThat(billing.handled()).containsExactly(
                new TicketChangedEvent("ticket-1", "opened"),
                new TicketChangedEvent("ticket-1", "closed")));
        }
    }

    @Test
    void publish_rolledBackTransaction_neverReachesBroker() {
        try (var context = start()) {
            // Given
            var events = context.getBean(EventPublisher.class);
            var transactions = context.getBean(TransactionProvider.class);
            var billing = context.getBean(BillingHandler.class);
            var failure = new IllegalStateException("Command failed after publishing");

            // When
            assertThatThrownBy(() -> transactions.doInTransaction(() -> {
                events.publish(new TicketChangedEvent("ticket-1", "rolled back"));
                throw failure;
            })).isSameAs(failure);
            events.publish(new TicketChangedEvent("ticket-1", "committed"));

            // Then
            await().atMost(ofSeconds(20)).untilAsserted(() -> assertThat(billing.handled()).containsExactly(
                new TicketChangedEvent("ticket-1", "committed")));
        }
    }

    private static ConfigurableApplicationContext start() {
        var topic = "ticket-events-" + UUID.randomUUID();
        var subscriberName = "ticket-service-" + UUID.randomUUID();
        createSubscription(topic, subscriberName + ".billing");
        return new SpringApplicationBuilder(JmsOutboxTestConfiguration.class)
            .web(NONE)
            .run(
                "--spring.datasource.url=jdbc:tc:postgresql:18-alpine:///smd?TC_INITSCRIPT=db/smd/event-store-schema.sql",
                "--spring.datasource.username=test_user",
                "--spring.datasource.password=test_password",
                "--smd.event-store.enabled=true",
                "--smd.event-store.scheduling.initial-delay=0ms",
                "--smd.event-store.scheduling.polling-delay=50ms",
                "--smd.jms.enabled=true",
                "--smd.jms.subscriber-name=" + subscriberName,
                "--smd.jms.outlet.receive-timeout=200ms",
                "--test.artemis.url=" + ARTEMIS.getBrokerUrl(),
                "--test.artemis.user=" + ARTEMIS.getUser(),
                "--test.artemis.password=" + ARTEMIS.getPassword(),
                "--test.artemis.topic=" + topic
            );
    }

    private static void createSubscription(String topic, String subscriptionName) {
        try (var connectionFactory = new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword());
             var context = connectionFactory.createContext()) {
            context.createSharedDurableConsumer(context.createTopic(topic), subscriptionName).close();
        }
    }
}
