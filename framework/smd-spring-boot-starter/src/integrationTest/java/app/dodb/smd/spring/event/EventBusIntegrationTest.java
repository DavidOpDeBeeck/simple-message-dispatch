package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.bus.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

class EventBusIntegrationTest {

    @ParameterizedTest(name = "publish with {0}")
    @ValueSource(classes = {
        EventIntegrationTestConfigurationWithDefaults.class,
        EventIntegrationTestConfigurationWithoutDefaults.class
    })
    void publish_withSynchronousDelivery_handlesEventBeforeReturning(Class<?> configClass) {
        // Given
        try (var context = createContext(configClass)) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();

            // When
            eventBus.publish(event);

            // Then
            assertThat(testEventHandler.getHandledEvents()).containsExactly(event);
        }
    }

    @Test
    void publish_withAsyncAwait_handlesEventBeforeReturning() {
        // Given
        try (var context = createContext(EventIntegrationTestConfigurationWithAsyncAwait.class)) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();

            // When
            eventBus.publish(event);

            // Then
            assertThat(testEventHandler.getHandledEvents()).containsExactly(event);
        }
    }

    @Test
    void publish_withAsyncFireAndForget_handlesEventEventually() throws InterruptedException {
        // Given
        try (var context = createContext(EventIntegrationTestConfigurationWithAsyncFireAndForget.class)) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();

            // When
            eventBus.publish(event);

            // Then
            testEventHandler.awaitEvent();
            assertThat(testEventHandler.getHandledEvents()).containsExactly(event);
        }
    }

    private ConfigurableApplicationContext createContext(Class<?> configClass) {
        return new SpringApplicationBuilder(configClass)
            .web(NONE)
            .run();
    }
}
