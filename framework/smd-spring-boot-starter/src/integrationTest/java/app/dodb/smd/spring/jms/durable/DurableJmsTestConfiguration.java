package app.dodb.smd.spring.jms.durable;

import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.eventstore.durable.DurableDelivery;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.jms.JmsConfig;
import app.dodb.smd.jms.JmsDestination;
import app.dodb.smd.jms.JmsEventMedium;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import static app.dodb.smd.api.event.delivery.EventSelector.eventType;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class DurableJmsTestConfiguration {

    @Bean
    public BillingHandler billingHandler() {
        return new BillingHandler();
    }

    @Bean
    public AuditHandler auditHandler() {
        return new AuditHandler();
    }

    @Bean
    public EventSerializer eventSerializer() {
        return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
    }

    @Bean
    public ConnectionFactory connectionFactory(@Value("${test.artemis.url}") String url,
                                               @Value("${test.artemis.user}") String user,
                                               @Value("${test.artemis.password}") String password) {
        return new ActiveMQConnectionFactory(url, user, password);
    }

    @Bean(destroyMethod = "close")
    public JmsEventMedium orderEvents(JmsConfig jms, @Value("${test.topic.orders}") String topic) {
        return new JmsEventMedium(jms, JmsDestination.topic(topic));
    }

    @Bean(destroyMethod = "close")
    public JmsEventMedium paymentEvents(JmsConfig jms, @Value("${test.topic.payments}") String topic) {
        return new JmsEventMedium(jms, JmsDestination.topic(topic));
    }

    @Bean
    public EventBusSpecCustomizer outboxes(DurableDelivery delivery,
                                           @Qualifier("orderEvents") JmsEventMedium orderEvents,
                                           @Qualifier("paymentEvents") JmsEventMedium paymentEvents) {
        var orders = delivery.outbox("orders.events", orderEvents.inlet()).selecting(eventType(OrderPlacedEvent.class));
        var payments = delivery.outbox("payments.events", paymentEvents.inlet()).selecting(eventType(PaymentReceivedEvent.class));
        return spec -> spec.sinks(orders, payments);
    }

    @Bean
    public EventSource billingInbox(DurableDelivery delivery,
                                    @Qualifier("orderEvents") JmsEventMedium orderEvents,
                                    @Qualifier("paymentEvents") JmsEventMedium paymentEvents) {
        return delivery.inbox("billing-service", orderEvents.outlet(), paymentEvents.outlet());
    }

    @Bean
    public ProcessingGroupsConfigurer inboxGroups(@Qualifier("billingInbox") EventSource billingInbox) {
        return groups -> groups
            .processingGroup("billing").source(billingInbox)
            .processingGroup("audit").source(billingInbox);
    }
}
