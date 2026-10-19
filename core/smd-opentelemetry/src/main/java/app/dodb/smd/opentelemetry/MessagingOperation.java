package app.dodb.smd.opentelemetry;

import io.opentelemetry.api.trace.SpanKind;

import static java.util.Objects.requireNonNull;

record MessagingOperation(String name, String type, SpanKind spanKind) {

    MessagingOperation {
        requireNonNull(name);
        requireNonNull(type);
        requireNonNull(spanKind);
    }
}
