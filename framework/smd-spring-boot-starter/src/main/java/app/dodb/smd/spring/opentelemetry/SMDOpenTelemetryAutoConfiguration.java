package app.dodb.smd.spring.opentelemetry;

import app.dodb.smd.opentelemetry.MessageTracing;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

import static app.dodb.smd.spring.SMDAutoConfiguration.TRACING_INTERCEPTOR_ORDER;

@AutoConfiguration(afterName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnBooleanProperty(name = "smd.opentelemetry.enabled", matchIfMissing = true)
public class SMDOpenTelemetryAutoConfiguration {

    @Bean
    @Order(TRACING_INTERCEPTOR_ORDER)
    @ConditionalOnBean(OpenTelemetry.class)
    @ConditionalOnMissingBean
    public MessageTracing messageTracing(OpenTelemetry openTelemetry) {
        return new MessageTracing(openTelemetry);
    }
}
