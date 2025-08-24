package app.dodb.smd.spring.event;

import app.dodb.smd.api.event.EventBus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

class EventBusIntegrationTest {

    @ParameterizedTest(name = "publish with {0}")
    @ValueSource(classes = {
        EventIntegrationTestConfigurationWithDefaults.class,
        EventIntegrationTestConfigurationWithoutDefaults.class
    })
    void publish(Class<?> configClass) {
        try (var context = new SpringApplicationBuilder(configClass).web(NONE).run()) {
            var eventBus = context.getBean(EventBus.class);
            var testEventHandler = context.getBean(TestEventHandler.class);

            var event = new TestEvent();

            eventBus.publish(event);

            assertThat(testEventHandler.getHandledEvents()).containsExactly(event);
        }
    }
}