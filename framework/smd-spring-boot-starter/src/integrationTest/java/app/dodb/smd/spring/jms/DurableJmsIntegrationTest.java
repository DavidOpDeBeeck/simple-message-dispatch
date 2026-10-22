package app.dodb.smd.spring.jms;

import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.spring.jms.durable.AuditHandler;
import app.dodb.smd.spring.jms.durable.BillingHandler;
import app.dodb.smd.spring.jms.durable.DurableJmsTestConfiguration;
import app.dodb.smd.spring.jms.durable.OrderPlacedEvent;
import app.dodb.smd.spring.jms.durable.PaymentReceivedEvent;
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
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

@Testcontainers
class DurableJmsIntegrationTest {

    @Container
    private static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.42.0")
        .withStartupTimeout(ofMinutes(3));

    @Test
    void publish_twoOutboxesIntoOneInbox_deliversEachEventToEveryGroupInSubjectOrder() {
        try (var context = start()) {
            // Given
            var events = context.getBean(EventPublisher.class);
            var billing = context.getBean(BillingHandler.class);
            var audit = context.getBean(AuditHandler.class);

            // When
            events.publish(new OrderPlacedEvent("order-1", 1));
            events.publish(new PaymentReceivedEvent("order-1", 1));
            events.publish(new OrderPlacedEvent("order-1", 2));
            events.publish(new PaymentReceivedEvent("order-1", 2));
            events.publish(new OrderPlacedEvent("order-2", 1));

            // Then
            await().atMost(ofSeconds(30)).untilAsserted(() -> {
                assertThat(billing.handled()).hasSize(5);
                assertThat(audit.handled()).hasSize(3);
            });
            assertThat(billing.handled()).filteredOn(OrderPlacedEvent.class::isInstance)
                .containsExactly(new OrderPlacedEvent("order-1", 1), new OrderPlacedEvent("order-1", 2), new OrderPlacedEvent("order-2", 1));
            assertThat(billing.handled()).filteredOn(PaymentReceivedEvent.class::isInstance)
                .containsExactly(new PaymentReceivedEvent("order-1", 1), new PaymentReceivedEvent("order-1", 2));
            assertThat(audit.handled())
                .containsExactly(new OrderPlacedEvent("order-1", 1), new OrderPlacedEvent("order-1", 2), new OrderPlacedEvent("order-2", 1));
        }
    }

    private static ConfigurableApplicationContext start() {
        var suffix = UUID.randomUUID();
        return new SpringApplicationBuilder(DurableJmsTestConfiguration.class)
            .web(NONE)
            .run(
                "--spring.datasource.url=jdbc:tc:postgresql:18-alpine:///smd?TC_INITSCRIPT=db/smd/durable-delivery-schema.sql",
                "--spring.datasource.username=test_user",
                "--spring.datasource.password=test_password",
                "--smd.durable-delivery.enabled=true",
                "--smd.durable-delivery.polling-delay=50ms",
                "--smd.jms.enabled=true",
                "--smd.jms.subscriber-name=billing-app-" + suffix,
                "--smd.jms.outlet.receive-timeout=200ms",
                "--test.artemis.url=" + ARTEMIS.getBrokerUrl(),
                "--test.artemis.user=" + ARTEMIS.getUser(),
                "--test.artemis.password=" + ARTEMIS.getPassword(),
                "--test.topic.orders=orders-" + suffix,
                "--test.topic.payments=payments-" + suffix
            );
    }
}
