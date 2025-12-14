package app.dodb.smd.api.event;

import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusIntegrationTest {

    @Test
    void publish() {
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .create();

        var event = new TestEvent();
        var anotherEvent = new AnotherTestEvent();
        eventBus.publish(event);
        eventBus.publish(anotherEvent);

        assertThat(TestEventHandler.handledEvents).containsExactly(event, anotherEvent);
    }

    @Test
    void publish_withInterceptor() {
        var interceptor = new EventBusInterceptorForTest();
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .interceptors(interceptor)
            .create();

        var event = new TestEvent();
        var anotherEvent = new AnotherTestEvent();
        eventBus.publish(event);
        eventBus.publish(anotherEvent);

        assertThat(interceptor.getInterceptedEvents()).containsExactly(event, anotherEvent);
    }
}