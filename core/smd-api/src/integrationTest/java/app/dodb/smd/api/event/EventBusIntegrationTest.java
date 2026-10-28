package app.dodb.smd.api.event;

import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
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

    @Test
    void create_withRouteNamedAfterAProcessingGroup_throwsException() {
        // Given
        var incoming = new SynchronousEventDispatcher();
        var outgoing = new SynchronousEventDispatcher();
        var spec = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()))
            .route("1", incoming.outlet(), outgoing.inlet());

        // When / Then
        assertThatThrownBy(spec::create)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Event route '1' has the name of another route or processing group.");
    }

    @Test
    void publish_intoMediumRoutedToProcessingGroups_handlesRoutedEvents() {
        // Given
        var outgoing = new SynchronousEventDispatcher();
        var incoming = new SynchronousEventDispatcher();
        var eventBus = EventBusSpec.withDefaults()
            .sinks(outgoing.inlet())
            .route("outgoing-to-incoming", outgoing.outlet(), incoming.inlet())
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()),
                groups -> groups.anyProcessingGroup().source(incoming.outlet()))
            .create();
        var event = new TestEvent();

        // When
        eventBus.publish(event);

        // Then
        assertThat(TestEventHandler.handledEvents).containsExactly(event);
    }

    @Test
    void publish_withMediumPerProcessingGroup_sendsEachMediumOnlyTheEventsItsGroupHandles() {
        // Given
        var firstMedium = new RecordingMedium();
        var secondMedium = new RecordingMedium();
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()),
                groups -> groups
                    .processingGroup("1").medium(firstMedium)
                    .processingGroup("2").medium(secondMedium))
            .create();
        var event = new TestEvent();
        var anotherEvent = new AnotherTestEvent();

        // When
        eventBus.publish(event);
        eventBus.publish(anotherEvent);

        // Then
        assertThat(firstMedium.received()).containsExactly(event);
        assertThat(secondMedium.received()).containsExactly(anotherEvent);
        assertThat(TestEventHandler.handledEvents).containsExactly(event, anotherEvent);
    }

    @Test
    void publish_eventHandledByGroupsSharingAMedium_handlesItOncePerGroup() {
        // Given
        var sharedMedium = new RecordingMedium();
        var eventBus = EventBusSpec.withDefaults()
            .processingGroups(new PackageBasedProcessingGroupLocator(List.of("app.dodb.smd.api.event"), new ConstructorBasedObjectCreator()),
                groups -> groups.anyProcessingGroup().medium(sharedMedium))
            .create();
        var event = new SharedTestEvent();

        // When
        eventBus.publish(event);

        // Then
        assertThat(sharedMedium.received()).containsExactly(event);
        assertThat(TestEventHandler.handledEvents).containsExactly(event, event);
    }

    private record RecordingMedium(SynchronousEventDispatcher dispatcher, List<Event> received) implements EventMedium {

        private RecordingMedium() {
            this(new SynchronousEventDispatcher(), new ArrayList<>());
        }

        @Override
        public EventSink inlet() {
            return new EventSink() {
                @Override
                public <E extends Event> void send(EventMessage<E> eventMessage) {
                    received.add(eventMessage.payload());
                    dispatcher.inlet().send(eventMessage);
                }

                @Override
                public EventDelivery delivery() {
                    return dispatcher.inlet().delivery();
                }
            };
        }

        @Override
        public EventSource outlet() {
            return dispatcher.outlet();
        }
    }

    private record RecordingProcessingGroupInterceptor(String name, List<String> interceptions) implements ProcessingGroupInterceptor {

        @Override
        public <E extends Event> void intercept(ProcessingGroupContext<E> context, ProcessingGroupInterceptorChain<E> chain) {
            interceptions.add(name + ":" + context.processingGroup() + ":" + context.eventMessage().payload());
            chain.proceed(context);
        }
    }
}
