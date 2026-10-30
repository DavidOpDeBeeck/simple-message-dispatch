package app.dodb.smd.spring.durable;

import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.durable.DurableDelivery;
import app.dodb.smd.durable.DurableEventDispatcherConfig;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.durable.storage.PendingDelivery;
import app.dodb.smd.durable.storage.postgres.PostgresDispatcherStorage;
import app.dodb.smd.serialization.ClassNameEventTypeResolver;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.serialization.jackson.JacksonEventSerializer;
import app.dodb.smd.serialization.jackson.SMDJacksonModule;
import app.dodb.smd.spring.EnableSMD;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.exponential;
import static app.dodb.smd.api.event.delivery.RetryBackoffStrategy.fixed;
import static org.assertj.core.api.Assertions.assertThat;

class SMDDurableDeliveryAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ConnectionProviderConfiguration.class);

    @Test
    void configure_enabled_createsDurableDeliveryFromProperties() {
        // Given / When / Then
        runner.withUserConfiguration(SerializerConfiguration.class)
            .withPropertyValues(
                "smd.durable-delivery.enabled=true",
                "smd.durable-delivery.scheduling.idle-backoff.base-delay=100ms",
                "smd.durable-delivery.scheduling.idle-backoff.multiplier=3",
                "smd.durable-delivery.scheduling.idle-backoff.max-delay=2s",
                "smd.durable-delivery.processing.batch-size=25",
                "smd.durable-delivery.processing.lane-count=8",
                "smd.durable-delivery.processing.retry-backoff.strategy=fixed",
                "smd.durable-delivery.processing.retry-backoff.fixed-delay=250ms")
            .run(context -> {
                assertThat(context).hasSingleBean(DurableDelivery.class);
                var config = context.getBean(DurableEventDispatcherConfig.class);
                assertThat(config.getDispatcherStorage()).isInstanceOf(PostgresDispatcherStorage.class);
                assertThat(config.getEventSerializer()).isSameAs(context.getBean(EventSerializer.class));
                assertThat(config.getSchedulingConfig().getIdleBackoffStrategy()).isEqualTo(exponential(Duration.ofMillis(100), 3.0, Duration.ofSeconds(2)));
                assertThat(config.getProcessingConfig().getBatchSize()).isEqualTo(25);
                assertThat(config.getProcessingConfig().getLaneCount()).isEqualTo(8);
                assertThat(config.getProcessingConfig().getRetryBackoffStrategy()).isEqualTo(fixed(Duration.ofMillis(250)));
            });
    }

    @Test
    void configure_withThreadPoolSize_createsSchedulerWithThatManyThreads() {
        // Given / When / Then
        runner.withUserConfiguration(SerializerConfiguration.class)
            .withPropertyValues("smd.durable-delivery.enabled=true", "smd.durable-delivery.scheduling.thread-pool-size=3")
            .run(context -> assertThat(context.getBean("smdDurableDeliveryScheduler", ScheduledThreadPoolExecutor.class).getCorePoolSize()).isEqualTo(3));
    }

    @Test
    void configure_disabled_createsNoDurableDelivery() {
        // Given / When / Then
        runner.withUserConfiguration(SerializerConfiguration.class)
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(DurableEventDispatcherConfig.class)
                .doesNotHaveBean(DurableDelivery.class));
    }

    @Test
    void configure_withDispatcherStorageBean_usesItInsteadOfPostgres() {
        // Given / When / Then
        runner.withUserConfiguration(SerializerConfiguration.class, DispatcherStorageConfiguration.class)
            .withPropertyValues("smd.durable-delivery.enabled=true")
            .run(context -> assertThat(context.getBean(DurableEventDispatcherConfig.class).getDispatcherStorage())
                .isSameAs(context.getBean(DispatcherStorage.class))
                .isInstanceOf(UnusedDispatcherStorage.class));
    }

    @Test
    void configure_enabledWithScheduling_keepsTheApplicationTaskScheduler() {
        // Given / When / Then
        runner.withConfiguration(AutoConfigurations.of(SMDDurableDeliveryAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SerializerConfiguration.class, SchedulingConfiguration.class)
            .withPropertyValues("smd.durable-delivery.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(TaskScheduler.class));
    }

    @Test
    void configure_enabledWithoutEventStore_createsEventSerializerFromJsonMapper() {
        // Given / When / Then
        runner.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(NoSerializerConfiguration.class)
            .withPropertyValues("smd.durable-delivery.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(EventSerializer.class);
                assertThat(context.getBean(DurableEventDispatcherConfig.class).getEventSerializer()).isSameAs(context.getBean(EventSerializer.class));
            });
    }

    @Test
    void configure_enabledWithoutJsonMapperOrEventSerializer_failsStartup() {
        // Given / When / Then
        runner.withUserConfiguration(NoSerializerConfiguration.class)
            .withPropertyValues("smd.durable-delivery.enabled=true")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(EventSerializer.class.getName()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSMD(packages = "app.dodb.smd.spring.durable.nohandlers")
    static class SerializerConfiguration {

        @Bean
        EventSerializer eventSerializer() {
            return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build(), new ClassNameEventTypeResolver(), List.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DispatcherStorageConfiguration {

        @Bean
        DispatcherStorage dispatcherStorage() {
            return new UnusedDispatcherStorage();
        }
    }

    static class UnusedDispatcherStorage implements DispatcherStorage {

        @Override
        public void store(String dispatcher, SerializedEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int registerDispatcher(String dispatcher, int laneCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerSubscriber(String dispatcher, String subscriber) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PendingDelivery> claimBatch(String dispatcher, String subscriber, int batchSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDelivered(String dispatcher, String subscriber, PendingDelivery delivery) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordFailure(String dispatcher, String subscriber, PendingDelivery delivery, Duration retryDelay, Throwable failure) {
            throw new UnsupportedOperationException();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSMD(packages = "app.dodb.smd.spring.durable.nohandlers")
    static class NoSerializerConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class ConnectionProviderConfiguration {

        @Bean
        ConnectionProvider connectionProvider() {
            return new ConnectionProvider() {
                @Override
                public Connection getConnection() {
                    throw new UnsupportedOperationException("No database in this test");
                }

                @Override
                public void releaseConnection(Connection connection) {
                }
            };
        }
    }
}
