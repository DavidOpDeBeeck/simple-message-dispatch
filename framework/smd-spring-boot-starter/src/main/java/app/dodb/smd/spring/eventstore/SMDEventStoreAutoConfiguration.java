package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.aggregate.EventSourcedAggregates;
import app.dodb.smd.eventstore.aggregate.PackageBasedAggregateLocator;
import app.dodb.smd.eventstore.processing.EventSequenceStore;
import app.dodb.smd.eventstore.processing.TokenStore;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.postgres.PostgresEventSequenceStore;
import app.dodb.smd.eventstore.storage.postgres.PostgresEventStorage;
import app.dodb.smd.eventstore.storage.postgres.PostgresTokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import app.dodb.smd.spring.SMDAutoConfiguration;
import app.dodb.smd.spring.SMDProperties;
import app.dodb.smd.spring.SpringConnectionProvider;
import app.dodb.smd.spring.serialization.SMDEventSerializationConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.List;

import static java.util.concurrent.Executors.newScheduledThreadPool;

@AutoConfiguration(after = JacksonAutoConfiguration.class, before = SMDAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "smd.event-store.enabled")
@EnableConfigurationProperties(SMDEventStoreProperties.class)
@Import(SMDEventSerializationConfiguration.class)
public class SMDEventStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConnectionProvider connectionProvider(DataSource smdDataSource) {
        return new SpringConnectionProvider(smdDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStorage eventStorage(ConnectionProvider smdConnectionProvider) {
        return new PostgresEventStorage(smdConnectionProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenStore tokenStore(ConnectionProvider smdConnectionProvider) {
        return new PostgresTokenStore(smdConnectionProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventSequenceStore eventSequenceStore(ConnectionProvider smdConnectionProvider) {
        return new PostgresEventSequenceStore(smdConnectionProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStoreConfig eventStoreConfig(EventStorage eventStorage,
                                             EventSerializer eventSerializer,
                                             TokenStore tokenStore,
                                             EventSequenceStore eventSequenceStore,
                                             TransactionProvider transactionProvider,
                                             SMDEventStoreProperties properties) {
        var scheduling = properties.getScheduling();
        var processing = properties.getProcessing();
        return EventStoreConfig.withoutDefaults()
            .eventStorage(eventStorage)
            .eventSerializer(eventSerializer)
            .tokenStore(tokenStore)
            .eventSequenceStore(eventSequenceStore)
            .transactionProvider(transactionProvider)
            .schedulingConfig(EventStoreConfig.SchedulingConfig.withoutDefaults()
                .enabled(scheduling.isEnabled())
                .scheduler(newScheduledThreadPool(scheduling.getThreadPoolSize()))
                .initialDelay(scheduling.getInitialDelay())
                .pollingDelay(scheduling.getPollingDelay())
                .build())
            .processingConfig(EventStoreConfig.ProcessingConfig.withoutDefaults()
                .maxRetries(processing.getMaxRetries())
                .batchSize(processing.getBatchSize())
                .gapTimeout(processing.getGapTimeout())
                .retryBackoffStrategy(processing.getRetryBackoff().createStrategy())
                .build())
            .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public EventStore eventStore(EventStoreConfig eventStoreConfig) {
        return new EventStore(eventStoreConfig);
    }

    @Bean
    public EventBusSpecCustomizer eventStoreSinkEventBusSpecCustomizer(EventStore eventStore) {
        return spec -> spec.sinks(eventStore.inlet());
    }

    @Bean
    @ConditionalOnMissingBean
    public EventSourcedAggregates eventSourcedAggregates(EventStore eventStore,
                                                         EventPublisher eventPublisher,
                                                         PrincipalProvider principalProvider,
                                                         TimeProvider timeProvider) {
        return new EventSourcedAggregates(eventStore, eventPublisher, new MetadataFactory(principalProvider, timeProvider));
    }

    @Bean
    public CommandHandlerLocator aggregateCommandHandlerLocator(List<SMDProperties> properties,
                                                                EventSourcedAggregates eventSourcedAggregates,
                                                                ObjectCreator objectCreator) {
        var packages = properties.stream()
            .map(SMDProperties::packages)
            .flatMap(List::stream)
            .toList();
        return new PackageBasedAggregateLocator(packages, eventSourcedAggregates, objectCreator);
    }
}
