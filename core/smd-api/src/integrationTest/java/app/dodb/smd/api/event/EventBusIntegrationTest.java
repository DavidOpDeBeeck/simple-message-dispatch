package app.dodb.smd.api.event;

import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventBusIntegrationTest {

    @BeforeEach
    void setUp() {
        TestEventHandler.handledEvents.clear();
    }

    @Test
    void publish_withDiscoveredHandlers_handlesEventsInOrder() {
        // Given
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .create();

        var event = new TestEvent();
        var anotherEvent = new AnotherTestEvent();

        // When
        eventBus.publish(event);
        eventBus.publish(anotherEvent);

        // Then
        assertThat(TestEventHandler.handledEvents).containsExactly(event, anotherEvent);
    }

    @Test
    void publish_withInterceptor_interceptsEventsInOrder() {
        // Given
        var interceptor = new EventInterceptorForTest();
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .interceptors(interceptor)
            .create();

        var event = new TestEvent();
        var anotherEvent = new AnotherTestEvent();

        // When
        eventBus.publish(event);
        eventBus.publish(anotherEvent);

        // Then
        assertThat(interceptor.getInterceptedEvents()).containsExactly(event, anotherEvent);
    }

    @Test
    void create_withDisabledProcessingGroup_skipsProcessingGroup() {
        // Given
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(
                new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()),
                spec -> spec
                    .processingGroup("1").disabled()
                    .anyProcessingGroup().sync()
            )
            .create();

        var event = new TestEvent();
        var anotherEvent = new AnotherTestEvent();

        // When
        eventBus.publish(event);
        eventBus.publish(anotherEvent);

        // Then
        assertThat(TestEventHandler.handledEvents).containsExactly(anotherEvent);
    }

    @Test
    void create_withUnconfiguredProcessingGroup_throwsException() {
        // Given
        var spec = EventBusSpec.withDefaults()
            .processingGroups(
                new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()),
                groups -> groups.processingGroup("1").sync()
            );

        // When / Then
        assertThatThrownBy(spec::create)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Processing group '2' has no configuration");
    }
}
