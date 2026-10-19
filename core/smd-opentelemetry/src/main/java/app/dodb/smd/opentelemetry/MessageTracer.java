package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.message.Message;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.function.Function;

import static app.dodb.smd.opentelemetry.MessageSpans.DESTINATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.ERROR_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.INSTRUMENTATION_SCOPE;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGING_SYSTEM;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.SYSTEM;
import static app.dodb.smd.opentelemetry.MessageSpans.THREAD_NAME;
import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static java.util.Objects.requireNonNull;

class MessageTracer {

    private final Tracer tracer;
    private final MetadataTraceContext traceContext;

    MessageTracer(OpenTelemetry openTelemetry) {
        this.tracer = requireNonNull(openTelemetry).getTracer(INSTRUMENTATION_SCOPE);
        this.traceContext = new MetadataTraceContext(openTelemetry.getPropagators().getTextMapPropagator());
    }

    <M extends Message<?, M>, R> R trace(MessagingOperation operation, Attributes attributes, M message, Function<M, R> work) {
        var current = Context.current();
        var withinSpan = Span.fromContext(current).getSpanContext().isValid();
        var carried = traceContext.extract(Context.root(), message.metadata());
        var carriedSpan = Span.fromContext(carried);
        var parent = withCarriedBaggage(withinSpan ? current : current.with(carriedSpan), carried);
        var link = carriedSpan.getSpanContext();
        var destination = message.payload().getClass().getName();

        var builder = tracer.spanBuilder(operation.name() + " " + destination)
            .setParent(parent)
            .setSpanKind(operation.spanKind())
            .setAllAttributes(attributes)
            .setAttribute(MESSAGING_SYSTEM, SYSTEM)
            .setAttribute(OPERATION_NAME, operation.name())
            .setAttribute(OPERATION_TYPE, operation.type())
            .setAttribute(DESTINATION_NAME, destination)
            .setAttribute(MESSAGE_ID, message.messageId().value().toString())
            .setAttribute(THREAD_NAME, currentThreadName());
        if (link.isValid()) {
            builder.addLink(link);
        }
        var span = builder.startSpan();
        var context = parent.with(span);

        try (var _ = context.makeCurrent()) {
            return work.apply(message.withMetadata(traceContext.inject(context, message.metadata())));
        } catch (Throwable failure) {
            span.setStatus(ERROR);
            span.setAttribute(ERROR_TYPE, failure.getClass().getName());
            throw failure;
        } finally {
            span.end();
        }
    }

    private static Context withCarriedBaggage(Context context, Context carried) {
        var carriedBaggage = Baggage.fromContext(carried);
        if (carriedBaggage.isEmpty()) {
            return context;
        }

        var merged = Baggage.fromContext(context).toBuilder();
        carriedBaggage.forEach((key, entry) -> merged.put(key, entry.getValue(), entry.getMetadata()));
        return context.with(merged.build());
    }

    private static String currentThreadName() {
        var thread = Thread.currentThread();
        if (thread.getName().isEmpty()) {
            return (thread.isVirtual() ? "virtual-" : "thread-") + thread.threadId();
        }
        return thread.getName();
    }
}
