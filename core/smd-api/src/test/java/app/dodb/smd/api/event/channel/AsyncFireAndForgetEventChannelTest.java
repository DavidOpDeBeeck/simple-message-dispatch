package app.dodb.smd.api.event.channel;

import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static app.dodb.smd.api.event.ProcessingGroup.DEFAULT;
import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AsyncFireAndForgetEventChannelTest {

    @Test
    void dispatch_withDifferentProcessingGroup() {
        var eventHandler = new EventHandlerWithDifferentProcessingGroup();
        var processingGroupOne = AnnotatedEventHandler.from(eventHandler).findBy("1");
        var processingGroupTwo = AnnotatedEventHandler.from(eventHandler).findBy("2");

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(processingGroupOne);
        channel.subscribe(processingGroupTwo);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1, 2));
    }

    @Test
    void dispatch_withDifferentOrders() {
        var eventHandler = new EventHandlerWithDifferentOrders();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1, 2, 3));
    }

    @Test
    void dispatch_whenEventHandlerThrowsExceptionInSameProcessingGroup_thenStopProcessingGroupExecution() {
        var eventHandler = new EventHandlerThatThrowsExceptionInSameProcessingGroup();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1));
    }

    @Test
    void dispatch_whenEventHandlerThrowsExceptionInDifferentProcessingGroup_thenStopProcessingGroupExecution() {
        var eventHandler = new EventHandlerThatThrowsExceptionInDifferentProcessingGroup();
        var processingGroupOne = AnnotatedEventHandler.from(eventHandler).findBy("1");
        var processingGroupTwo = AnnotatedEventHandler.from(eventHandler).findBy("2");

        var channel = AsyncFireAndForgetEventChannel.usingVirtualThreads();
        channel.subscribe(processingGroupOne);
        channel.subscribe(processingGroupTwo);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        await().untilAsserted(() ->
            assertThat(eventHandler.getMethodCalled())
                .containsOnly(1, 2));
    }

    public record EventForTest(String value) implements Event {
    }

    public static class EventHandlerWithDifferentProcessingGroup {

        private final List<Integer> methodCalled = new ArrayList<>();

        @EventHandler
        @ProcessingGroup("1")
        public void handle(EventForTest event) {
            methodCalled.add(1);
        }

        @EventHandler
        @ProcessingGroup("2")
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    @ProcessingGroup
    public static class EventHandlerWithDifferentOrders {

        private final List<Integer> methodCalled = new ArrayList<>();

        @EventHandler(order = 1)
        public void handle(EventForTest event) {
            methodCalled.add(1);
        }

        @EventHandler(order = 3)
        public void handle3(EventForTest event) {
            methodCalled.add(3);
        }

        @EventHandler(order = 2)
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    @ProcessingGroup
    public static class EventHandlerThatThrowsExceptionInSameProcessingGroup {

        private final List<Integer> methodCalled = new ArrayList<>();

        @EventHandler(order = 1)
        public void handle(EventForTest event) {
            methodCalled.add(1);
            throw new RuntimeException("this is an exception");
        }

        @EventHandler(order = 2)
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }

    public static class EventHandlerThatThrowsExceptionInDifferentProcessingGroup {

        private final List<Integer> methodCalled = new ArrayList<>();

        @ProcessingGroup("1")
        @EventHandler(order = 1)
        public void handle(EventForTest event) {
            methodCalled.add(1);
            throw new RuntimeException("this is an exception");
        }

        @ProcessingGroup("2")
        @EventHandler(order = 2)
        public void handle2(EventForTest event) {
            methodCalled.add(2);
        }

        public List<Integer> getMethodCalled() {
            return methodCalled;
        }
    }
}