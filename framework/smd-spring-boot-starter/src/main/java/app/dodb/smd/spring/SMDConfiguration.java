package app.dodb.smd.spring;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandlerLocator;
import app.dodb.smd.api.command.PackageBasedCommandHandlerLocator;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.command.bus.TransactionalCommandBusInterceptor;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.PackageBasedProcessingGroupLocator;
import app.dodb.smd.api.event.ProcessingGroupLocator;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.event.bus.TransactionalEventBusInterceptor;
import app.dodb.smd.api.framework.ObjectCreator;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.time.SystemTimeProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.query.PackageBasedQueryHandlerLocator;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandlerLocator;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import app.dodb.smd.api.query.bus.TransactionalQueryBusInterceptor;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig;
import app.dodb.smd.eventstore.framework.ConnectionProvider;
import app.dodb.smd.eventstore.store.EventSerializer;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.JacksonEventSerializer;
import app.dodb.smd.eventstore.store.JdbcEventStorage;
import app.dodb.smd.eventstore.store.JdbcTokenStore;
import app.dodb.smd.eventstore.store.TokenStore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.defaultSynchronous;
import static app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer.multi;
import static com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

@AutoConfiguration
public class SMDConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectCreator objectCreator(ApplicationContext applicationContext) {
        return new SpringObjectCreator(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public PrincipalProvider principalProvider() {
        return new SimplePrincipalProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeProvider timeProvider() {
        return new SystemTimeProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionProvider transactionProvider() {
        return new SpringTransactionProvider();
    }

    @Bean
    public CommandHandlerLocator commandHandlerLocator(List<SMDProperties> properties, ObjectCreator objectCreator) {
        List<String> packages = combinePackages(properties);
        return new PackageBasedCommandHandlerLocator(packages, objectCreator);
    }

    @Bean
    public ProcessingGroupLocator processingGroupLocator(List<SMDProperties> properties, ObjectCreator objectCreator) {
        List<String> packages = combinePackages(properties);
        return new PackageBasedProcessingGroupLocator(packages, objectCreator);
    }

    @Bean
    public QueryHandlerLocator queryHandlerLocator(List<SMDProperties> properties, ObjectCreator objectCreator) {
        List<String> packages = combinePackages(properties);
        return new PackageBasedQueryHandlerLocator(packages, objectCreator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessingGroupsConfigurer processingGroupsConfigurer() {
        return defaultSynchronous();
    }

    @Bean
    @Order(HIGHEST_PRECEDENCE)
    public CommandBusInterceptor transactionalCommandBusInterceptor(TransactionProvider transactionProvider) {
        return new TransactionalCommandBusInterceptor(transactionProvider);
    }

    @Bean
    @Order(HIGHEST_PRECEDENCE)
    public EventBusInterceptor transactionalEventBusInterceptor(TransactionProvider transactionProvider) {
        return new TransactionalEventBusInterceptor(transactionProvider);
    }

    @Bean
    @Order(HIGHEST_PRECEDENCE)
    public QueryBusInterceptor transactionalQueryBusInterceptor(TransactionProvider transactionProvider) {
        return new TransactionalQueryBusInterceptor(transactionProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandGateway commandGateway(TimeProvider timeProvider,
                                         PrincipalProvider principalProvider,
                                         CommandHandlerLocator locator,
                                         List<CommandBusInterceptor> interceptors) {
        return CommandBusSpec.withoutDefaults()
            .time(timeProvider)
            .principal(principalProvider)
            .commandHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(TimeProvider timeProvider,
                                         PrincipalProvider principalProvider,
                                         ProcessingGroupLocator locator,
                                         List<ProcessingGroupsConfigurer> processingGroupsConfigurers,
                                         List<EventBusInterceptor> interceptors) {
        return EventBusSpec.withoutDefaults()
            .time(timeProvider)
            .principal(principalProvider)
            .processingGroups(locator, multi(processingGroupsConfigurers))
            .interceptors(interceptors)
            .create();
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryGateway queryGateway(TimeProvider timeProvider,
                                     PrincipalProvider principalProvider,
                                     QueryHandlerLocator locator,
                                     List<QueryBusInterceptor> interceptors) {
        return QueryBusSpec.withoutDefaults()
            .time(timeProvider)
            .principal(principalProvider)
            .queryHandlers(locator)
            .interceptors(interceptors)
            .create();
    }

    private static List<String> combinePackages(List<SMDProperties> properties) {
        return properties.stream()
            .map(SMDProperties::packages)
            .flatMap(List::stream)
            .toList();
    }

    @Configuration
    @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    @ConditionalOnProperty(name = "smd.event-store.enabled", havingValue = "true")
    @EnableConfigurationProperties(SMDEventStoreProperties.class)
    public static class EventStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ConnectionProvider connectionProvider(DataSource smdDataSource) {
            return new SpringConnectionProvider(smdDataSource);
        }

        @Bean
        @ConditionalOnMissingBean
        public EventStorage eventStorage(ConnectionProvider smdConnectionProvider) {
            return new JdbcEventStorage(smdConnectionProvider);
        }

        @Bean
        @ConditionalOnMissingBean
        public TokenStore tokenStore(ConnectionProvider smdConnectionProvider) {
            return new JdbcTokenStore(smdConnectionProvider);
        }

        @Bean
        @ConditionalOnMissingBean(name = "eventObjectMapper")
        public ObjectMapper eventObjectMapper() {
            var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Principal.class)
                .build();
            var typeResolver = new DefaultTypeResolverBuilder(NON_FINAL, typeValidator) {
                @Override
                public boolean useForType(JavaType t) {
                    return Principal.class.isAssignableFrom(t.getRawClass());
                }
            };

            return new ObjectMapper()
                .disable(FAIL_ON_EMPTY_BEANS)
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .setDefaultTyping(typeResolver.init(JsonTypeInfo.Id.CLASS, null)
                    .inclusion(JsonTypeInfo.As.PROPERTY)
                    .typeProperty("type"));
        }

        @Bean
        @ConditionalOnMissingBean
        public EventSerializer eventSerializer(@Qualifier("eventObjectMapper") ObjectMapper objectMapper) {
            return new JacksonEventSerializer(objectMapper);
        }

        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean(name = "eventStoreScheduler")
        public ScheduledExecutorService eventStoreScheduler(SMDEventStoreProperties properties) {
            return Executors.newScheduledThreadPool(properties.getScheduling().getThreadPoolSize());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean({EventStorage.class, EventSerializer.class, TokenStore.class})
        public EventStoreChannelConfig eventStoreProcessingConfig(TransactionProvider transactionProvider,
                                                                  EventStorage eventStorage,
                                                                  EventSerializer eventSerializer,
                                                                  TokenStore tokenStore,
                                                                  List<EventBusInterceptor> interceptors,
                                                                  @Qualifier("eventStoreScheduler") ScheduledExecutorService scheduler,
                                                                  SMDEventStoreProperties properties) {
            var scheduling = properties.getScheduling();
            var processing = properties.getProcessing();
            return EventStoreChannelConfig.withoutDefaults()
                .transactionProvider(transactionProvider)
                .interceptors(interceptors)
                .eventStorage(eventStorage)
                .eventSerializer(eventSerializer)
                .tokenStore(tokenStore)
                .schedulingConfig(EventStoreChannelConfig.SchedulingConfig.withoutDefaults()
                    .enabled(scheduling.isEnabled())
                    .scheduler(scheduler)
                    .initialDelay(scheduling.getInitialDelay())
                    .pollingDelay(scheduling.getPollingDelay())
                    .build())
                .processingConfig(EventStoreChannelConfig.ProcessingConfig.withoutDefaults()
                    .maxRetries(processing.getMaxRetries())
                    .batchSize(processing.getBatchSize())
                    .gapTimeout(processing.getGapTimeout())
                    .retryBackoffStrategy(processing.getRetryBackoff().createStrategy())
                    .build())
                .build();
        }

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        @ConditionalOnBean(EventStoreChannelConfig.class)
        public EventStoreChannel eventStoreChannel(EventStoreChannelConfig eventStoreChannelConfig) {
            return new EventStoreChannel(eventStoreChannelConfig);
        }
    }
}
