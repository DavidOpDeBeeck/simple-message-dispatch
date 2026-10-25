package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.event.ProcessingGroupRegistry;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.delivery.EventRoute;
import app.dodb.smd.api.event.delivery.SynchronousEventDispatcher;
import app.dodb.smd.opentelemetry.EventPublicationTracingTest.OrderPlaced;
import app.dodb.smd.opentelemetry.EventPublicationTracingTest.RecordingSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static app.dodb.smd.opentelemetry.MessageSpans.DELIVERY;
import static app.dodb.smd.opentelemetry.MessageSpans.ROUTE;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class RouteTracingTest {

    @RegisterExtension
    private final RecordedSpans spans = new RecordedSpans();
    private final MessageTracing tracing = new MessageTracing(spans.openTelemetry());

    @Test
    void publish_intoRoutedMedium_recordsASendSpanForThePublicationAndOneForTheRoute() {
        var outgoing = new SynchronousEventDispatcher();
        var broker = new RecordingSink("broker");
        var eventBus = EventBusSpec.withDefaults()
            .sinks(outgoing.inlet())
            .route("relay", outgoing.outlet(), broker)
            .processingGroups(ProcessingGroupRegistry::empty)
            .interceptors(tracing)
            .sinkInterceptors(tracing)
            .create();

        eventBus.publish(new OrderPlaced());

        var sends = spans.spans("send", OrderPlaced.class);
        assertThat(sends).extracting(send -> send.getAttributes().get(DELIVERY), send -> send.getAttributes().get(ROUTE))
            .containsExactlyInAnyOrder(tuple("sync", null), tuple("broker", "relay"));
    }

    @Test
    void route_eventFromSourceOutsideASpan_recordsItsSendInTheCarriedTrace() {
        var stored = new RecordingSink("outbox");
        EventBusSpec.withDefaults()
            .sinks(stored)
            .processingGroups(ProcessingGroupRegistry::empty)
            .interceptors(tracing)
            .create()
            .publish(new OrderPlaced());
        var upstream = new SynchronousEventDispatcher();
        var broker = new RecordingSink("broker");
        EventBusSpec.withDefaults()
            .route("relay", upstream.outlet(), broker)
            .sinkInterceptors(tracing)
            .create();

        upstream.send(stored.received().getFirst());

        assertThat(spans.span("send", OrderPlaced.class))
            .hasKind(PRODUCER)
            .hasParent(spans.span("publish", OrderPlaced.class))
            .hasAttribute(DELIVERY, "broker")
            .hasAttribute(ROUTE, "relay");
    }
}
