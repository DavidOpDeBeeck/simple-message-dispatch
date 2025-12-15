package app.dodb.smd.api.event;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import org.junit.jupiter.api.Test;

import static java.lang.Integer.MIN_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class AnnotatedEventHandlerTest {

    @Test
    void handle_withEventParameter() {
        var registry = AnnotatedEventHandler.from(new EventHandlerWithEventParameter());

        assertThat(registry.eventHandlerRegistryByProcessingGroup().values())
            .flatExtracting(ProcessingGroupRegistry.EventHandlerRegistry::eventHandlers)
            .extracting(EventHandlerBehaviour::eventType, EventHandlerBehaviour::order, AnnotatedEventHandler::processingGroup)
            .containsExactly(tuple(EventForTest.class, MIN_VALUE, "default"));
    }

    @Test
    void handle_withProcessingGroupOnClass() {
        var registry = AnnotatedEventHandler.from(new EventHandlerWithProcessingGroupOnClass());

        assertThat(registry.eventHandlerRegistryByProcessingGroup().values())
            .flatExtracting(ProcessingGroupRegistry.EventHandlerRegistry::eventHandlers)
            .extracting(EventHandlerBehaviour::eventType, EventHandlerBehaviour::order, AnnotatedEventHandler::processingGroup)
            .containsExactly(tuple(EventForTest.class, MIN_VALUE, "processingGroupOnClass"));
    }

    @Test
    void handle_withProcessingGroupOnMethod() {
        var registry = AnnotatedEventHandler.from(new EventHandlerWithProcessingGroupOnMethod());

        assertThat(registry.eventHandlerRegistryByProcessingGroup().values())
            .flatExtracting(ProcessingGroupRegistry.EventHandlerRegistry::eventHandlers)
            .extracting(EventHandlerBehaviour::eventType, EventHandlerBehaviour::order, AnnotatedEventHandler::processingGroup)
            .containsExactly(tuple(EventForTest.class, MIN_VALUE, "processingGroupOnMethod"));
    }

    @Test
    void handle_withProcessingGroupOnClassAndMethod() {
        var registry = AnnotatedEventHandler.from(new EventHandlerWithProcessingGroupOnClassAndMethod());

        assertThat(registry.eventHandlerRegistryByProcessingGroup().values())
            .flatExtracting(ProcessingGroupRegistry.EventHandlerRegistry::eventHandlers)
            .extracting(EventHandlerBehaviour::eventType, EventHandlerBehaviour::order, AnnotatedEventHandler::processingGroup)
            .containsExactly(tuple(EventForTest.class, MIN_VALUE, "processingGroupOnMethod"));
    }

    @Test
    void handle_withReturnType() {
        assertThatThrownBy(() -> AnnotatedEventHandler.from(new EventHandlersWithReturnType()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event handler: return type mismatch");
    }

    @Test
    void handle_withoutEventParameter() {
        assertThatThrownBy(() -> AnnotatedEventHandler.from(new EventHandlerWithoutEventParameter()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event handler: method must include a parameter of type Event.");
    }

    @Test
    void handle_withEventAndMetadataParameter() {
        var registry = AnnotatedEventHandler.from(new EventHandlerWithEventAndMetadataParameter());

        assertThat(registry.eventHandlerRegistryByProcessingGroup()).hasSize(1);
    }

    @Test
    void handle_withoutParameters() {
        assertThatThrownBy(() -> AnnotatedEventHandler.from(new EventHandlerWithoutParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event handler: method must have at least one parameter.");
    }

    @Test
    void handle_withMultipleEventTypes() {
        assertThatThrownBy(() -> AnnotatedEventHandler.from(new EventHandlerWithMultipleEventTypes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event handler: method must only include one Event as a parameter.");
    }

    @Test
    void handle_withMultipleHandlersForSameEventAndOrder() {
        assertThatThrownBy(() -> AnnotatedEventHandler.from(new MultipleEventHandlersForSameEventAndOrder()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Event handlers with same order found");
    }

    @Test
    void handle_withMultipleHandlersForSameEventAndOrder_butDifferentProcessingGroup() {
        var registry = AnnotatedEventHandler.from(new MultipleEventHandlersForSameEventAndOrderButDifferentProcessingGroup());

        assertThat(registry.eventHandlerRegistryByProcessingGroup().values())
            .flatExtracting(ProcessingGroupRegistry.EventHandlerRegistry::eventHandlers)
            .extracting(EventHandlerBehaviour::eventType, EventHandlerBehaviour::order, AnnotatedEventHandler::processingGroup)
            .containsExactlyInAnyOrder(
                tuple(EventForTest.class, MIN_VALUE, "processingGroup1"),
                tuple(EventForTest.class, MIN_VALUE, "processingGroup2")
            );
    }

    public record EventForTest() implements Event {
    }

    public record AnotherEventForTest() implements Event {
    }

    @ProcessingGroup
    public static class EventHandlerWithEventParameter {

        @EventHandler
        public void handle(EventForTest event) {
        }
    }

    @ProcessingGroup
    public static class EventHandlerWithEventAndMetadataParameter {

        @EventHandler
        public void handle(EventForTest event, Metadata metadata, MessageId messageId) {
        }
    }

    @ProcessingGroup("processingGroupOnClass")
    public static class EventHandlerWithProcessingGroupOnClass {

        @EventHandler
        public void handle(EventForTest event) {
        }
    }

    public static class EventHandlerWithProcessingGroupOnMethod {

        @EventHandler
        @ProcessingGroup("processingGroupOnMethod")
        public void handle(EventForTest event) {
        }
    }

    @ProcessingGroup("processingGroupOnClass")
    public static class EventHandlerWithProcessingGroupOnClassAndMethod {

        @EventHandler
        @ProcessingGroup("processingGroupOnMethod")
        public void handle(EventForTest event) {
        }
    }

    public static class EventHandlersWithReturnType {

        @EventHandler
        public String handle(EventForTest event) {
            return "test";
        }
    }

    @ProcessingGroup
    public static class MultipleEventHandlersForSameEventAndOrder {

        @EventHandler
        public void handle(EventForTest event) {
        }

        @EventHandler
        public void handle2(EventForTest event) {
        }
    }

    public static class MultipleEventHandlersForSameEventAndOrderButDifferentProcessingGroup {

        @EventHandler
        @ProcessingGroup("processingGroup1")
        public void handle(EventForTest event) {
        }

        @EventHandler
        @ProcessingGroup("processingGroup2")
        public void handle2(EventForTest event) {
        }
    }

    public static class EventHandlerWithoutEventParameter {

        @EventHandler
        public void handle(Metadata metadata) {
        }
    }

    public static class EventHandlerWithoutParameters {

        @EventHandler
        public void handle() {
        }
    }

    public static class EventHandlerWithMultipleEventTypes {

        @EventHandler
        public void handle(EventForTest event, AnotherEventForTest anotherEvent) {
        }
    }
}