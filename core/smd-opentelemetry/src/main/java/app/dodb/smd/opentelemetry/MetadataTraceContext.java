package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.metadata.Metadata;
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
            return carrier.properties().keySet();
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
        var properties = new HashMap<>(metadata.properties());
        propagator.fields().forEach(properties::remove);
        propagator.inject(context, properties, Map::put);
        return new Metadata(metadata.principal(), metadata.timestamp(), metadata.parentMessageId(), properties);
    }
}
