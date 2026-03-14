package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.bus.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

class EventBusIntegrationTest {

    @ParameterizedTest(name = "publish with {0}")
    @ValueSource(classes = {
        EventIntegrationTestConfigurationWithDefaults.class,
        EventIntegrationTestConfigurationWithoutDefaults.class
    })
    void publish(Class<?> configClass) {
        try (var context = createContext(configClass)) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();

            eventBus.publish(event);

            assertThat(testEventHandler.getHandledEvents()).containsExactly(event);
        }
    }

    @Test
    void publish_withEventStore() {
        try (var context = createContext(EventIntegrationTestConfigurationWithEventStore.class, "event-store")) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();
            eventBus.publish(event);

            await().untilAsserted(() -> assertThat(testEventHandler.getHandledEvents()).contains(event));
        }
    }

    @Test
    void publish_withAsyncAwait() {
        try (var context = createContext(EventIntegrationTestConfigurationWithAsyncAwait.class)) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();
            eventBus.publish(event);

            assertThat(testEventHandler.getHandledEvents()).contains(event);
        }
    }

    @Test
    void publish_withAsyncFireAndForget() {
        try (var context = createContext(EventIntegrationTestConfigurationWithAsyncFireAndForget.class)) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();
            eventBus.publish(event);

            await().untilAsserted(() -> assertThat(testEventHandler.getHandledEvents()).contains(event));
        }
    }

    private ConfigurableApplicationContext createContext(Class<?> configClass, String... profiles) {
        return new SpringApplicationBuilder(configClass)
            .profiles(profiles)
            .web(NONE)
            .run();
    }
}