package app.dodb.smd.spring.durable;

import app.dodb.smd.eventstore.durable.DurableDelivery;
import app.dodb.smd.eventstore.durable.DurableDeliveryConfig;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.spring.EnableSMD;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.time.Duration;

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
                "smd.durable-delivery.polling-delay=50ms",
                "smd.durable-delivery.stall-warning-after=30s")
            .run(context -> {
                var config = context.getBean(DurableDeliveryConfig.class);
                assertThat(context).hasSingleBean(DurableDelivery.class);
                assertThat(config.getConnectionProvider()).isSameAs(context.getBean(ConnectionProvider.class));
                assertThat(config.getEventSerializer()).isSameAs(context.getBean(EventSerializer.class));
                assertThat(config.getPollingDelay()).isEqualTo(Duration.ofMillis(50));
                assertThat(config.getStallWarningAfter()).isEqualTo(Duration.ofSeconds(30));
            });
    }

    @Test
    void configure_disabled_createsNoDurableDelivery() {
        // Given / When / Then
        runner.withUserConfiguration(SerializerConfiguration.class)
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(DurableDelivery.class));
    }

    @Test
    void configure_enabledWithoutEventSerializer_failsStartup() {
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
            return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
        }
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
