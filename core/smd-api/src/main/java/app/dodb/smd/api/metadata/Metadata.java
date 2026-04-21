package app.dodb.smd.api.metadata;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.principal.Principal;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public record Metadata(Principal principal, Instant timestamp, MessageId parentMessageId, Map<String, String> properties) {

    public Metadata {
        properties = Map.copyOf(properties);
    }

    public Metadata(Principal principal, Instant timestamp, MessageId parentMessageId) {
        this(principal, timestamp, parentMessageId, Map.of());
    }

    public Metadata and(Metadata metadata) {
        var combined = new HashMap<>(this.properties);
        combined.putAll(metadata.properties());

        return new Metadata(
            metadata.principal() != null ? metadata.principal() : this.principal,
            metadata.timestamp() != null ? metadata.timestamp() : this.timestamp,
            metadata.parentMessageId() != null ? metadata.parentMessageId() : this.parentMessageId,
            combined
        );
    }
}
