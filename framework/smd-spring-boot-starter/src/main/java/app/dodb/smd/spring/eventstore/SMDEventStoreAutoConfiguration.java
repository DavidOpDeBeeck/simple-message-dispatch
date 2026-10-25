package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.PackageBasedCommandHandlerLocator;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.EventStore;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.sequence.EventSequenceStore;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.TokenStore;
import app.dodb.smd.eventstore.storage.postgres.PostgresEventStorage;
import app.dodb.smd.eventstore.storage.postgres.PostgresEventSequenceStore;
import app.dodb.smd.eventstore.storage.postgres.PostgresTokenStore;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import app.dodb.smd.spring.SMDAutoConfiguration;
import app.dodb.smd.spring.SMDProperties;
import app.dodb.smd.spring.SpringConnectionProvider;
import app.dodb.smd.spring.serialization.SMDEventSerializationConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;
import javax.sql.DataSource;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.springframework.util.ClassUtils.resolveClassName;

@AutoConfiguration(after = JacksonAutoConfiguration.class, before = SMDAutoConfiguration.class)
@ConditionalOnProperty(name = "smd.event-store.enabled", havingValue = "true")
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
    public CommandHandlerLocator commandHandlerLocator(List<SMDProperties> properties,
                                                       ObjectCreator objectCreator,
                                                       EventStore eventStore,
                                                       EventPublisher eventPublisher) {
        var packages = properties.stream()
            .map(SMDProperties::packages)
            .flatMap(List::stream)
            .toList();
        var commandHandlers = new PackageBasedCommandHandlerLocator(packages, objectCreator, type -> !type.isAnnotationPresent(Aggregate.class));
        var aggregateCommandHandlers = eventStore.aggregateCommandHandlers(findAggregateClasses(packages), eventPublisher);
        return () -> commandHandlers.locate().and(aggregateCommandHandlers.locate());
    }

    private static List<Class<?>> findAggregateClasses(List<String> packages) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Aggregate.class));
        return packages.stream()
            .flatMap(packageName -> scanner.findCandidateComponents(packageName).stream())
            .<Class<?>>map(beanDefinition -> resolveClassName(requireNonNull(beanDefinition.getBeanClassName()), SMDEventStoreAutoConfiguration.class.getClassLoader()))
            .distinct()
            .toList();
    }
}
