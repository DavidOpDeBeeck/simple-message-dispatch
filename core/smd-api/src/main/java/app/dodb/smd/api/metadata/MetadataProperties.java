package app.dodb.smd.api.metadata;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableMap;

public record MetadataProperties(Map<String, String> asMap) {

    private static final MetadataProperties EMPTY = new MetadataProperties(Map.of());

    public static MetadataProperties empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MetadataProperties of(Map<String, String> properties) {
        return new MetadataProperties(Map.copyOf(properties));
    }

    public MetadataProperties {
        requireNonNull(asMap);
    }

    public Optional<String> find(String name) {
        return Optional.ofNullable(asMap.get(name));
    }

    public String get(String name) {
        return asMap.get(name);
    }

    public boolean contains(String name) {
        return asMap.containsKey(name);
    }

    public Set<String> names() {
        return asMap.keySet();
    }

    public boolean isEmpty() {
        return asMap.isEmpty();
    }

    public MetadataProperties with(String name, String value) {
        return with(of(Map.of(name, value)));
    }

    public MetadataProperties with(MetadataProperties other) {
        if (other.isEmpty()) {
            return this;
        }
        var combined = new HashMap<>(asMap);
        combined.putAll(other.asMap());
        return of(combined);
    }

    public MetadataProperties without(String... names) {
        return without(List.of(names));
    }

    public MetadataProperties without(Collection<String> names) {
        var remaining = new HashMap<>(asMap);
        names.forEach(remaining::remove);
        return of(remaining);
    }

    public MetadataProperties inheritedBy(MetadataInheritance inheritance) {
        return new MetadataProperties(asMap.entrySet().stream()
            .filter(property -> inheritance.inherits(property.getKey()))
            .collect(toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public static class Builder {

        private final Map<String, String> properties = new HashMap<>();

        private Builder() {
        }

        public Builder with(String name, String value) {
            properties.put(name, value);
            return this;
        }

        public Builder with(String name, Optional<String> value) {
            value.ifPresent(present -> properties.put(name, present));
            return this;
        }

        public MetadataProperties build() {
            return of(properties);
        }
    }
}
