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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynchronousEventChannelTest {

    @Test
    void dispatch_withDifferentProcessingGroup() {
        var eventHandler = new EventHandlerWithDifferentProcessingGroup();
        var processingGroupOne = AnnotatedEventHandler.from(eventHandler).findBy("1");
        var processingGroupTwo = AnnotatedEventHandler.from(eventHandler).findBy("2");

        var channel = new SynchronousEventChannel();
        channel.subscribe(processingGroupOne);
        channel.subscribe(processingGroupTwo);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        assertThat(eventHandler.getMethodCalled())
            .containsOnly(1, 2);
    }

    @Test
    void dispatch_withDifferentOrders() {
        var eventHandler = new EventHandlerWithDifferentOrders();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = new SynchronousEventChannel();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");
        channel.send(EventMessage.from(event, METADATA));

        assertThat(eventHandler.getMethodCalled())
            .containsExactly(1, 2, 3);
    }

    @Test
    void dispatch_whenEventHandlerThrowsException_thenRethrow() {
        var eventHandler = new EventHandlerThatThrowsException();
        var registry = AnnotatedEventHandler.from(eventHandler).findBy(DEFAULT);

        var channel = new SynchronousEventChannel();
        channel.subscribe(registry);

        EventForTest event = new EventForTest("Hello world");

        assertThatThrownBy(() -> channel.send(EventMessage.from(event, METADATA)))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("this is an exception");
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
    public static class EventHandlerThatThrowsException {

        @EventHandler
        public void handle(EventForTest event) {
            throw new RuntimeException("this is an exception");
        }
    }
}