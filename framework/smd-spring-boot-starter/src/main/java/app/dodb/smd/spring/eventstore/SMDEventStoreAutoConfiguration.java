package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;
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
import app.dodb.smd.eventstore.store.serialization.SMDJacksonModule;
import app.dodb.smd.spring.SMDAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;

import static java.util.concurrent.Executors.newScheduledThreadPool;
import static tools.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;

@AutoConfiguration(after = JacksonAutoConfiguration.class, before = SMDAutoConfiguration.class)
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
    @ConditionalOnMissingBean
    public EventTypeResolver eventTypeResolver() {
        return new ClassNameEventTypeResolver();
    }

    @Bean
    @ConditionalOnBean(JsonMapper.Builder.class)
    @ConditionalOnMissingBean(EventSerializer.class)
    public EventSerializer eventSerializer(JsonMapper.Builder jsonMapperBuilder, EventTypeResolver eventTypeResolver) {
        var objectMapper = jsonMapperBuilder
            .disable(FAIL_ON_EMPTY_BEANS)
            .addModule(new SMDJacksonModule())
            .build();
        return new JacksonEventSerializer(objectMapper, eventTypeResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStoreChannelConfig eventStoreProcessingConfig(EventStorage eventStorage,
                                                              EventSerializer eventSerializer,
                                                              TokenStore tokenStore,
                                                              TransactionProvider transactionProvider,
                                                              SMDEventStoreProperties properties) {
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
