package app.dodb.smd.spring.durable;

import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.durable.DurableDelivery;
import app.dodb.smd.durable.DurableEventDispatcherConfig;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.durable.storage.postgres.PostgresDispatcherStorage;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.spring.SMDAutoConfiguration;
import app.dodb.smd.spring.SpringConnectionProvider;
import app.dodb.smd.spring.eventstore.SMDEventStoreAutoConfiguration;
import app.dodb.smd.spring.serialization.SMDEventSerializationConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@AutoConfiguration(after = {JacksonAutoConfiguration.class, SMDEventStoreAutoConfiguration.class}, before = SMDAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "smd.durable-delivery.enabled")
@EnableConfigurationProperties(SMDDurableDeliveryProperties.class)
@Import(SMDEventSerializationConfiguration.class)
public class SMDDurableDeliveryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConnectionProvider connectionProvider(DataSource smdDataSource) {
        return new SpringConnectionProvider(smdDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public DispatcherStorage dispatcherStorage(ConnectionProvider smdConnectionProvider) {
        return new PostgresDispatcherStorage(smdConnectionProvider);
    }

    @Bean(destroyMethod = "close", defaultCandidate = false)
    public ScheduledExecutorService smdDurableDeliveryScheduler(SMDDurableDeliveryProperties properties) {
        return new ScheduledThreadPoolExecutor(properties.getScheduling().getThreadPoolSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableEventDispatcherConfig durableEventDispatcherConfig(DispatcherStorage dispatcherStorage,
                                                                     TransactionProvider transactionProvider,
                                                                     EventSerializer eventSerializer,
                                                                     @Qualifier("smdDurableDeliveryScheduler") ScheduledExecutorService scheduler,
                                                                     SMDDurableDeliveryProperties properties) {
        var scheduling = properties.getScheduling();
        var processing = properties.getProcessing();
        return DurableEventDispatcherConfig.withoutDefaults()
            .dispatcherStorage(dispatcherStorage)
            .transactionProvider(transactionProvider)
            .eventSerializer(eventSerializer)
            .schedulingConfig(DurableEventDispatcherConfig.SchedulingConfig.withoutDefaults()
                .scheduler(scheduler)
                .idleBackoffStrategy(scheduling.getIdleBackoff().createStrategy())
                .build())
            .processingConfig(DurableEventDispatcherConfig.ProcessingConfig.withoutDefaults()
                .batchSize(processing.getBatchSize())
                .laneCount(processing.getLaneCount())
                .retryBackoffStrategy(processing.getRetryBackoff().createStrategy())
                .build())
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableDelivery durableDelivery(DurableEventDispatcherConfig config) {
        return new DurableDelivery(config);
    }
}
