package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.principal.Principal;
import app.dodb.smd.eventstore.channel.EventStoreChannel;
import app.dodb.smd.eventstore.channel.EventStoreChannelConfig;
import app.dodb.smd.eventstore.framework.ConnectionProvider;
import app.dodb.smd.eventstore.store.EventStorage;
import app.dodb.smd.eventstore.store.JdbcEventStorage;
import app.dodb.smd.eventstore.store.JdbcTokenStore;
import app.dodb.smd.eventstore.store.TokenStore;
import app.dodb.smd.eventstore.store.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.eventstore.store.serialization.EventSerializer;
import app.dodb.smd.eventstore.store.serialization.EventTypeResolver;
import app.dodb.smd.eventstore.store.serialization.JacksonEventSerializer;
import app.dodb.smd.spring.SMDAutoConfiguration;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

import static com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;

@AutoConfiguration(before = SMDAutoConfiguration.class)
@ConditionalOnProperty(name = "smd.event-store.enabled", havingValue = "true")
@EnableConfigurationProperties(SMDEventStoreProperties.class)
public class SMDEventStoreAutoConfiguration {

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
    public EventTypeResolver eventTypeResolver() {
        return new ClassNameEventTypeResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventSerializer eventSerializer(@Qualifier("eventObjectMapper") ObjectMapper objectMapper,
                                           EventTypeResolver eventTypeResolver) {
        return new JacksonEventSerializer(objectMapper, eventTypeResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStoreChannelConfig eventStoreProcessingConfig(EventStorage eventStorage,
                                                              EventSerializer eventSerializer,
                                                              TokenStore tokenStore,
                                                              PlatformTransactionManager transactionManager,
                                                              TransactionProvider transactionProvider,
                                                              SMDEventStoreProperties properties) {
        requireNonNull(transactionManager, "A PlatformTransactionManager is required when the SMD event store is enabled");

        var scheduling = properties.getScheduling();
        var processing = properties.getProcessing();
        return EventStoreChannelConfig.withoutDefaults()
            .eventStorage(eventStorage)
            .eventSerializer(eventSerializer)
            .tokenStore(tokenStore)
            .transactionProvider(transactionProvider)
            .interceptors(List.of())
            .schedulingConfig(EventStoreChannelConfig.SchedulingConfig.withoutDefaults()
                .enabled(scheduling.isEnabled())
                .scheduler(newScheduledThreadPool(scheduling.getThreadPoolSize()))
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
    public EventStoreChannel eventStoreChannel(EventStoreChannelConfig eventStoreChannelConfig) {
        return new EventStoreChannel(eventStoreChannelConfig);
    }
}
