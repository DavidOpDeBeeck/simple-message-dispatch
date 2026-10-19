package app.dodb.smd.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

class RecordedSpans implements AfterEachCallback {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setPropagators(ContextPropagators.create(TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
        .build();

    OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    Span startApplicationSpan(String name) {
        return tracerProvider.get("application").spanBuilder(name).startSpan();
    }

    List<SpanData> finished() {
        return exporter.getFinishedSpanItems();
    }

    SpanData named(String name) {
        return finished().stream().filter(span -> span.getName().equals(name)).findFirst().orElseThrow();
    }

    SpanData span(String operation, Class<?> payloadType) {
        return named(operation + " " + payloadType.getName());
    }

    List<SpanData> spans(String operation, Class<?> payloadType) {
        return finished().stream().filter(span -> span.getName().equals(operation + " " + payloadType.getName())).toList();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        openTelemetry.close();
    }
}
