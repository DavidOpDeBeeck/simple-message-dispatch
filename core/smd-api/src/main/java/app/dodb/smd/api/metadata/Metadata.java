package app.dodb.smd.api.metadata;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.principal.Principal;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record Metadata(Principal principal, Instant timestamp, MessageId parentMessageId, MetadataProperties properties) {

    public static Metadata withProperties(Map<String, String> properties) {
        return withProperties(MetadataProperties.of(properties));
    }

    public static Metadata withProperties(MetadataProperties properties) {
        return new Metadata(null, null, null, properties);
    }

    public static Metadata withProperty(String name, String value) {
        return withProperties(Map.of(name, value));
    }

    public Metadata {
        requireNonNull(properties);
    }

    public Metadata(Principal principal, Instant timestamp, MessageId parentMessageId, Map<String, String> properties) {
        this(principal, timestamp, parentMessageId, MetadataProperties.of(properties));
    }

    public Metadata(Principal principal, Instant timestamp, MessageId parentMessageId) {
        this(principal, timestamp, parentMessageId, MetadataProperties.empty());
    }

    public Optional<String> property(String name) {
        return properties.find(name);
    }

    public Metadata and(Metadata metadata) {
        return new Metadata(
            metadata.principal() != null ? metadata.principal() : this.principal,
            metadata.timestamp() != null ? metadata.timestamp() : this.timestamp,
            metadata.parentMessageId() != null ? metadata.parentMessageId() : this.parentMessageId,
            properties.with(metadata.properties())
        );
    }
}
