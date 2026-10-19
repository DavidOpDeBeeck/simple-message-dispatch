package app.dodb.smd.api.event;

import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        var interceptor = new EventBusInterceptorForTest();
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
    void publish_withProcessingGroupInterceptors_interceptsOnlyHandlingGroupAroundHandlersInOrder() {
        // Given
        var interceptions = new ArrayList<String>();
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .processingGroupInterceptors(new RecordingProcessingGroupInterceptor("outer", interceptions), new RecordingProcessingGroupInterceptor("inner", interceptions))
            .create();

        var event = new TestEvent();

        // When
        eventBus.publish(event);

        // Then
        assertThat(interceptions).containsExactly("outer:1:" + event, "inner:1:" + event);
        assertThat(TestEventHandler.handledEvents).containsExactly(event);
    }

    @Test
    void publish_withProcessingGroupInterceptorAndFailingHandler_propagatesFailureThroughInterceptor() {
        // Given
        var failures = new ArrayList<Throwable>();
        ProcessingGroupInterceptor interceptor = new ProcessingGroupInterceptor() {
            @Override
            public <E extends Event> void intercept(ProcessingGroupContext<E> context, ProcessingGroupInterceptorChain<E> chain) {
                try {
                    chain.proceed(context);
                } catch (RuntimeException e) {
                    failures.add(e);
                    throw e;
                }
            }
        };
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .processingGroupInterceptors(interceptor)
            .create();

        // When / Then
        assertThatThrownBy(() -> eventBus.publish(new FailingTestEvent()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("handler failed")
            .satisfies(failure -> assertThat(failures).containsExactly(failure));
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

    private record RecordingProcessingGroupInterceptor(String name, List<String> interceptions) implements ProcessingGroupInterceptor {

        @Override
        public <E extends Event> void intercept(ProcessingGroupContext<E> context, ProcessingGroupInterceptorChain<E> chain) {
            interceptions.add(name + ":" + context.processingGroup() + ":" + context.eventMessage().payload());
            chain.proceed(context);
        }
    }
}
