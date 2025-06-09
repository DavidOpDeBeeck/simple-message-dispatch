package app.dodb.smd.api.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventHandlerDispatcherTest {

    @Test
    void dispatch_withDifferentProcessingGroup() {
        var eventHandler = new EventHandlerWithDifferentProcessingGroup();
        var dispatcher = new EventHandlerDispatcher(() -> AnnotatedEventHandler.from(eventHandler));

        EventForTest event = new EventForTest("Hello world");
        dispatcher.dispatch(EventMessage.from(event, METADATA));

        assertThat(eventHandler.getMethodCalled())
            .containsOnly(1, 2);
    }

    @Test
    void dispatch_withDifferentOrders() {
        var eventHandler = new EventHandlerWithDifferentOrders();
        var dispatcher = new EventHandlerDispatcher(() -> AnnotatedEventHandler.from(eventHandler));

        EventForTest event = new EventForTest("Hello world");
        dispatcher.dispatch(EventMessage.from(event, METADATA));

        assertThat(eventHandler.getMethodCalled())
            .containsExactly(1, 2, 3);
    }

    @Test
    void dispatch_whenEventHandlerThrowsException_thenRethrow() {
        var dispatcher = new EventHandlerDispatcher(() -> AnnotatedEventHandler.from(new EventHandlerThatThrowsException()));

        EventForTest event = new EventForTest("Hello world");

        assertThatThrownBy(() -> dispatcher.dispatch(EventMessage.from(event, METADATA)))
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

    public static class EventHandlerThatThrowsException {

        @EventHandler
        public void handle(EventForTest event) {
            throw new RuntimeException("this is an exception");
        }
    }
}