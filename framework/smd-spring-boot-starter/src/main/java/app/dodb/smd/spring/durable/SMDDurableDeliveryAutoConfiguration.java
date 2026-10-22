package app.dodb.smd.spring.durable;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.durable.DurableDelivery;
import app.dodb.smd.eventstore.durable.DurableDeliveryConfig;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.spring.SMDAutoConfiguration;
import app.dodb.smd.spring.eventstore.SMDEventStoreAutoConfiguration;
import app.dodb.smd.spring.eventstore.SpringConnectionProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

import static java.util.concurrent.Executors.newScheduledThreadPool;

@AutoConfiguration(after = SMDEventStoreAutoConfiguration.class, before = SMDAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "smd.durable-delivery.enabled")
@EnableConfigurationProperties(SMDDurableDeliveryProperties.class)
public class SMDDurableDeliveryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConnectionProvider connectionProvider(DataSource smdDataSource) {
        return new SpringConnectionProvider(smdDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableDeliveryConfig durableDeliveryConfig(ConnectionProvider connectionProvider,
                                                       TransactionProvider transactionProvider,
                                                       EventSerializer eventSerializer,
                                                       SMDDurableDeliveryProperties properties) {
        return DurableDeliveryConfig.withoutDefaults()
            .connectionProvider(connectionProvider)
            .transactionProvider(transactionProvider)
            .eventSerializer(eventSerializer)
            .scheduler(newScheduledThreadPool(properties.getThreadPoolSize()))
            .pollingDelay(properties.getPollingDelay())
            .stallWarningAfter(properties.getStallWarningAfter())
            .retryBackoffStrategy(DurableDeliveryConfig.DEFAULT_RETRY_BACKOFF_STRATEGY)
            .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public DurableDelivery durableDelivery(DurableDeliveryConfig durableDeliveryConfig) {
        return new DurableDelivery(durableDeliveryConfig);
    }
}
