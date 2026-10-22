package app.dodb.smd.spring.jms;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.jms.JmsConfig;
import app.dodb.smd.jms.JmsEventMedium;
import app.dodb.smd.spring.eventstore.SMDEventStoreAutoConfiguration;
import jakarta.jms.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = SMDEventStoreAutoConfiguration.class)
@ConditionalOnClass({JmsEventMedium.class, ConnectionFactory.class})
@ConditionalOnBooleanProperty(name = "smd.jms.enabled")
@EnableConfigurationProperties(SMDJmsProperties.class)
public class SMDJmsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JmsConfig jmsConfig(ConnectionFactory connectionFactory,
                               EventSerializer eventSerializer,
                               TransactionProvider transactionProvider,
                               SMDJmsProperties properties) {
        return JmsConfig.withoutDefaults()
            .connectionFactory(connectionFactory)
            .eventSerializer(eventSerializer)
            .transactionProvider(transactionProvider)
            .subscriberName(properties.getSubscriberName())
            .concurrency(properties.getOutlet().getConcurrency())
            .receiveTimeout(properties.getOutlet().getReceiveTimeout())
            .build();
    }
}
