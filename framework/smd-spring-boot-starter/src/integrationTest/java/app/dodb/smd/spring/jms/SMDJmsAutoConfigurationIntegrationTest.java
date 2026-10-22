package app.dodb.smd.spring.jms;

import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.jms.JmsConfig;
import app.dodb.smd.spring.EnableSMD;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SMDJmsAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SerializerConfiguration.class);

    @Test
    void configure_enabled_createsJmsConfigFromProperties() {
        // Given / When / Then
        runner.withUserConfiguration(ConnectionFactoryConfiguration.class)
            .withPropertyValues(
                "smd.jms.enabled=true",
                "smd.jms.subscriber-name=ticket-service",
                "smd.jms.outlet.concurrency=3",
                "smd.jms.outlet.receive-timeout=250ms")
            .run(context -> {
                var config = context.getBean(JmsConfig.class);
                assertThat(config.getConnectionFactory()).isSameAs(context.getBean(ConnectionFactory.class));
                assertThat(config.getEventSerializer()).isSameAs(context.getBean(EventSerializer.class));
                assertThat(config.getSubscriberName()).contains("ticket-service");
                assertThat(config.getConcurrency()).isEqualTo(3);
                assertThat(config.getReceiveTimeout()).isEqualTo(Duration.ofMillis(250));
            });
    }

    @Test
    void configure_disabled_createsNoJmsConfig() {
        // Given / When / Then
        runner.withUserConfiguration(ConnectionFactoryConfiguration.class)
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(JmsConfig.class));
    }

    @Test
    void configure_enabledWithoutConnectionFactory_failsStartup() {
        // Given / When / Then
        runner.withPropertyValues("smd.jms.enabled=true")
            .run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(ConnectionFactory.class.getName()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSMD(packages = "app.dodb.smd.spring.jms.nohandlers")
    static class SerializerConfiguration {

        @Bean
        EventSerializer eventSerializer() {
            return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConnectionFactoryConfiguration {

        @Bean
        ConnectionFactory connectionFactory() {
            return new ActiveMQConnectionFactory("tcp://localhost:1");
        }
    }
}
