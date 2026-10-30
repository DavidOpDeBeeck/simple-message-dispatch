package app.dodb.smd.api.metadata;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.principal.Principal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record Metadata(Principal principal,
                       Instant timestamp,
                       MessageId parentMessageId,
                       MetadataProperties properties,
                       MetadataProperties transientProperties) {

    public static Metadata at(Instant timestamp) {
        return new Metadata(null, timestamp, null, MetadataProperties.empty(), MetadataProperties.empty());
    }

    public static Metadata now() {
        return at(Instant.now());
    }

    public Metadata {
        requireNonNull(timestamp);
        requireNonNull(properties);
        requireNonNull(transientProperties);
    }

    public Optional<String> property(String name) {
        return transientProperties.find(name).or(() -> properties.find(name));
    }

    public Metadata withPrincipal(Principal principal) {
        return new Metadata(principal, timestamp, parentMessageId, properties, transientProperties);
    }

    public Metadata withTimestamp(Instant timestamp) {
        return new Metadata(principal, timestamp, parentMessageId, properties, transientProperties);
    }

    public Metadata withParentMessageId(MessageId parentMessageId) {
        return new Metadata(principal, timestamp, parentMessageId, properties, transientProperties);
    }

    public Metadata withProperty(String name, String value) {
        return new Metadata(principal, timestamp, parentMessageId, properties.with(name, value), transientProperties);
    }

    public Metadata withProperties(MetadataProperties other) {
        return new Metadata(principal, timestamp, parentMessageId, properties.with(other), transientProperties);
    }

    public Metadata withoutProperties(String... names) {
        return withoutProperties(List.of(names));
    }

    public Metadata withoutProperties(Collection<String> names) {
        return new Metadata(principal, timestamp, parentMessageId, properties.without(names), transientProperties);
    }

    public Metadata withTransientProperty(String name, String value) {
        return new Metadata(principal, timestamp, parentMessageId, properties, transientProperties.with(name, value));
    }

    public Metadata withTransientProperties(MetadataProperties other) {
        return new Metadata(principal, timestamp, parentMessageId, properties, transientProperties.with(other));
    }
}
