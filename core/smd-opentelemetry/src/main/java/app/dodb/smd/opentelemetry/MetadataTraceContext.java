package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataProperties;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

class MetadataTraceContext {

    private static final TextMapGetter<Metadata> METADATA = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier.properties().names();
        }

        @Override
        public String get(Metadata carrier, String key) {
            return carrier.properties().get(key);
        }
    };

    private final TextMapPropagator propagator;

    MetadataTraceContext(TextMapPropagator propagator) {
        this.propagator = requireNonNull(propagator);
    }

    Context extract(Context context, Metadata metadata) {
        return propagator.extract(context, metadata, METADATA);
    }

    Metadata inject(Context context, Metadata metadata) {
        var traceContext = new HashMap<String, String>();
        propagator.inject(context, traceContext, Map::put);
        var properties = metadata.properties().without(propagator.fields()).with(MetadataProperties.of(traceContext));
        return new Metadata(metadata.principal(), metadata.timestamp(), metadata.parentMessageId(), properties);
    }
}
