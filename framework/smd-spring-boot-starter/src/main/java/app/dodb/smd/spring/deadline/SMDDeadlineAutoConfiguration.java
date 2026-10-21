package app.dodb.smd.spring.deadline;

import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.deadline.DeadlineScheduler;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.metadata.principal.PrincipalProvider;
import app.dodb.smd.api.metadata.time.TimeProvider;
import app.dodb.smd.eventstore.deadline.DeadlineStorage;
import app.dodb.smd.eventstore.deadline.DeadlineStore;
import app.dodb.smd.eventstore.deadline.DeadlineStoreConfig;
import app.dodb.smd.eventstore.serialization.ClassNameCommandTypeResolver;
import app.dodb.smd.eventstore.serialization.CommandTypeResolver;
import app.dodb.smd.eventstore.serialization.DeadlineSerializer;
import app.dodb.smd.eventstore.serialization.JacksonDeadlineSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.postgres.PostgresDeadlineStorage;
import app.dodb.smd.spring.SMDAutoConfiguration;
import app.dodb.smd.spring.eventstore.SMDEventStoreAutoConfiguration;
import app.dodb.smd.spring.eventstore.SpringConnectionProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static tools.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;

@AutoConfiguration(after = {JacksonAutoConfiguration.class, SMDAutoConfiguration.class, SMDEventStoreAutoConfiguration.class})
@ConditionalOnProperty(name = "smd.deadlines.enabled", havingValue = "true")
@EnableConfigurationProperties(SMDDeadlineProperties.class)
public class SMDDeadlineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConnectionProvider connectionProvider(DataSource smdDataSource) {
        return new SpringConnectionProvider(smdDataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadlineStorage deadlineStorage(ConnectionProvider smdConnectionProvider) {
        return new PostgresDeadlineStorage(smdConnectionProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandTypeResolver commandTypeResolver() {
        return new ClassNameCommandTypeResolver();
    }

    @Bean
    @ConditionalOnBean(JsonMapper.Builder.class)
    @ConditionalOnMissingBean(DeadlineSerializer.class)
    public DeadlineSerializer deadlineSerializer(JsonMapper.Builder jsonMapperBuilder, CommandTypeResolver commandTypeResolver) {
        var objectMapper = jsonMapperBuilder
            .disable(FAIL_ON_EMPTY_BEANS)
            .addModule(new SMDJacksonModule())
            .build();
        return new JacksonDeadlineSerializer(objectMapper, commandTypeResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadlineStoreConfig deadlineStoreConfig(TransactionProvider transactionProvider,
                                                   PrincipalProvider principalProvider,
                                                   TimeProvider timeProvider,
                                                   DeadlineStorage deadlineStorage,
                                                   DeadlineSerializer deadlineSerializer,
                                                   SMDDeadlineProperties properties) {
        var scheduling = properties.getScheduling();
        var processing = properties.getProcessing();
        return DeadlineStoreConfig.withoutDefaults()
            .transactionProvider(transactionProvider)
            .principalProvider(principalProvider)
            .timeProvider(timeProvider)
            .deadlineStorage(deadlineStorage)
            .deadlineSerializer(deadlineSerializer)
            .scheduler(newSingleThreadScheduledExecutor(Thread.ofPlatform().name("smd-deadlines-", 1).factory()))
            .initialDelay(scheduling.getInitialDelay())
            .pollingDelay(scheduling.getPollingDelay())
            .batchSize(processing.getBatchSize())
            .maxRetries(processing.getMaxRetries())
            .retryBackoffStrategy(processing.getRetryBackoff().createStrategy())
            .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DeadlineScheduler.class)
    public DeadlineStore deadlineStore(DeadlineStoreConfig deadlineStoreConfig) {
        return new DeadlineStore(deadlineStoreConfig);
    }

    @Bean
    @ConditionalOnBean(DeadlineStore.class)
    @ConditionalOnProperty(name = "smd.deadlines.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    public DeadlineStoreLifecycle deadlineStoreLifecycle(DeadlineStore deadlineStore, CommandGateway commandGateway) {
        return new DeadlineStoreLifecycle(deadlineStore, commandGateway);
    }
}
