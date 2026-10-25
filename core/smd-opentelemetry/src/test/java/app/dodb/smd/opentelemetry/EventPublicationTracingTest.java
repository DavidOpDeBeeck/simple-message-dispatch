package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroupRegistry;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.metadata.Metadata;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.opentelemetry.MessageSpans.DELIVERY;
import static app.dodb.smd.opentelemetry.MessageSpans.DESTINATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.ERROR_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.SpanAssertions.linksTo;
import static app.dodb.smd.opentelemetry.SpanAssertions.traceparentOf;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPublicationTracingTest {

    @RegisterExtension
    private final RecordedSpans spans = new RecordedSpans();
    private final MessageTracing tracing = new MessageTracing(spans.openTelemetry());

    @Test
    void publish_event_recordsAProducerPublishSpan() {
        var message = EventMessage.from(new OrderPlaced(), Metadata.withProperties(Map.of()));

        eventBus().publish(message);

        var publication = spans.span("publish", OrderPlaced.class);
        assertThat(publication)
            .hasKind(PRODUCER)
            .hasNoParent()
            .hasTotalRecordedLinks(0)
            .hasAttributesSatisfying(
                equalTo(OPERATION_TYPE, "send"),
                equalTo(OPERATION_NAME, "publish"),
                equalTo(DESTINATION_NAME, OrderPlaced.class.getName()),
                equalTo(MESSAGE_KIND, "event"),
                equalTo(MESSAGE_ID, message.messageId().value().toString()));
        assertThat(publication.getAttributes().get(DELIVERY)).isNull();
    }

    @Test
    void publish_sinks_recordsASendSpanPerSinkWithinThePublication() {
        var eventBus = eventBus(new RecordingSink("shared-log"), new RecordingSink("audit-log"));

        eventBus.publish(new OrderPlaced());

        var publication = spans.span("publish", OrderPlaced.class);
        var sends = spans.spans("send", OrderPlaced.class);
        assertThat(sends).extracting(send -> send.getAttributes().get(DELIVERY)).containsExactly("shared-log", "audit-log");
        assertThat(sends).allSatisfy(send -> assertThat(send)
            .hasKind(PRODUCER)
            .hasParent(publication)
            .hasLinksSatisfying(linksTo(publication))
            .hasAttributesSatisfying(
                equalTo(OPERATION_TYPE, "send"),
                equalTo(OPERATION_NAME, "send"),
                equalTo(DESTINATION_NAME, OrderPlaced.class.getName()),
                equalTo(MESSAGE_KIND, "event")));
    }

    @Test
    void publish_sink_receivesTheEventCarryingThePublication() {
        var sink = new RecordingSink("log");

        eventBus(sink).publish(new OrderPlaced());

        assertThat(sink.received).singleElement()
            .satisfies(event -> assertThat(event.metadata().properties().asMap())
                .containsEntry("traceparent", traceparentOf(spans.span("publish", OrderPlaced.class))));
    }

    @Test
    void publish_failingSink_rethrowsTheFailureAndMarksItsSendAndThePublicationFailed() {
        var failure = new IllegalStateException("log unavailable");
        var eventBus = eventBus(new FailingSink(failure));

        assertThatThrownBy(() -> eventBus.publish(new OrderPlaced())).isSameAs(failure);

        assertThat(spans.span("send", OrderPlaced.class))
            .hasStatus(StatusData.error())
            .hasAttribute(ERROR_TYPE, IllegalStateException.class.getName());
        assertThat(spans.span("publish", OrderPlaced.class))
            .hasStatus(StatusData.error())
            .hasAttribute(ERROR_TYPE, IllegalStateException.class.getName());
    }

    private EventBus eventBus(EventSink... sinks) {
        return EventBusSpec.withDefaults()
            .sinks(sinks)
            .processingGroups(ProcessingGroupRegistry::empty)
            .interceptors(tracing)
            .sinkInterceptors(tracing)
            .create();
    }

    record OrderPlaced() implements Event {
    }

    static class RecordingSink implements EventSink {

        private final EventDelivery delivery;
        private final List<EventMessage<?>> received = new CopyOnWriteArrayList<>();

        RecordingSink(String delivery) {
            this.delivery = new EventDelivery(delivery);
        }

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            received.add(eventMessage);
        }

        @Override
        public EventDelivery delivery() {
            return delivery;
        }
    }

    record FailingSink(RuntimeException failure) implements EventSink {

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            throw failure;
        }

        @Override
        public EventDelivery delivery() {
            return new EventDelivery("failing-log");
        }
    }
}
