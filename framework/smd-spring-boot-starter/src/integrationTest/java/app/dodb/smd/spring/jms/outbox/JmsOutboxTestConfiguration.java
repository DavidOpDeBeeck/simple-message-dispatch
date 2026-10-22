package app.dodb.smd.spring.jms.outbox;

import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.jms.JmsConfig;
import app.dodb.smd.jms.JmsDestination;
import app.dodb.smd.jms.JmsEventMedium;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableAutoConfiguration
@EnableSMD
public class JmsOutboxTestConfiguration {

    @Bean
    public BillingHandler billingHandler() {
        return new BillingHandler();
    }

    @Bean
    public ConnectionFactory connectionFactory(@Value("${test.artemis.url}") String url,
                                               @Value("${test.artemis.user}") String user,
                                               @Value("${test.artemis.password}") String password) {
        return new ActiveMQConnectionFactory(url, user, password);
    }

    @Bean(destroyMethod = "close")
    public JmsEventMedium ticketEvents(JmsConfig jms, @Value("${test.artemis.topic}") String topic) {
        return new JmsEventMedium(jms, JmsDestination.topic(topic));
    }

    @Bean
    public EventBusSpecCustomizer ticketEventsOutbox(EventStore eventStore, JmsEventMedium ticketEvents) {
        return spec -> spec.sinks(eventStore.outbox("ticket-events", ticketEvents.inlet()));
    }

    @Bean
    public ProcessingGroupsConfigurer billingGroups(EventStore eventStore, JmsEventMedium ticketEvents) {
        return groups -> groups.processingGroup("billing").source(eventStore.inbox(ticketEvents.outlet()));
    }
}
