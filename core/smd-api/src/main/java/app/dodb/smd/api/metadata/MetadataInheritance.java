package app.dodb.smd.api.metadata;

import java.util.Set;

import static java.util.Objects.requireNonNull;

public interface MetadataInheritance {

    String SMD_PREFIX = "smd.";

    boolean inherits(String property);

    static MetadataInheritance excludingSmdProperties() {
        return excludingPrefix(SMD_PREFIX);
    }

    static MetadataInheritance excludingPrefix(String prefix) {
        requireNonNull(prefix);
        return property -> !property.startsWith(prefix);
    }

    static MetadataInheritance excludingProperties(String... properties) {
        var excluded = Set.of(properties);
        return property -> !excluded.contains(property);
    }

    default MetadataInheritance and(MetadataInheritance other) {
        requireNonNull(other);
        return property -> inherits(property) && other.inherits(property);
    }
}
