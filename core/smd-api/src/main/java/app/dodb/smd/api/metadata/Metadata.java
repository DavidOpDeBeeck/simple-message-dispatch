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

    public static Builder builder() {
        return new Builder();
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

    public static class Builder {

        private Principal principal;
        private Instant timestamp;
        private MessageId parentMessageId;
        private MetadataProperties properties = MetadataProperties.empty();
        private MetadataProperties transientProperties = MetadataProperties.empty();

        private Builder() {
        }

        public Builder principal(Principal principal) {
            this.principal = principal;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder parentMessageId(MessageId parentMessageId) {
            this.parentMessageId = parentMessageId;
            return this;
        }

        public Builder property(String name, String value) {
            properties = properties.with(name, value);
            return this;
        }

        public Builder properties(MetadataProperties properties) {
            this.properties = this.properties.with(properties);
            return this;
        }

        public Builder transientProperty(String name, String value) {
            transientProperties = transientProperties.with(name, value);
            return this;
        }

        public Builder transientProperties(MetadataProperties properties) {
            transientProperties = transientProperties.with(properties);
            return this;
        }

        public Metadata build() {
            if (timestamp == null) {
                throw new IllegalStateException("Invalid metadata: a timestamp is required; set it with Metadata.builder().timestamp(...)");
            }
            return new Metadata(principal, timestamp, parentMessageId, properties, transientProperties);
        }
    }
}
