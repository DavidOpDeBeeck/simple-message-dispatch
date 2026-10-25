package app.dodb.smd.api.metadata;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static app.dodb.smd.api.metadata.MetadataInheritance.excludingSmdProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataPropertiesTest {

    @Test
    void of_namesAndValues_holdsEachPair() {
        var properties = MetadataProperties.of("tenant", "acme", "Idempotency-Key", "key-1");

        assertThat(properties.asMap()).containsOnly(Map.entry("tenant", "acme"), Map.entry("Idempotency-Key", "key-1"));
    }

    @Test
    void of_unpairedNamesAndValues_fails() {
        assertThatThrownBy(() -> MetadataProperties.of("tenant", "acme", "Idempotency-Key"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("MetadataProperties.of requires names and values in pairs; got 3 arguments");
    }

    @Test
    void of_map_isNotChangedByLaterChangesToTheMap() {
        var source = new HashMap<>(Map.of("tenant", "acme"));
        var properties = MetadataProperties.of(source);

        source.put("tenant", "other");

        assertThat(properties.get("tenant")).isEqualTo("acme");
    }

    @Test
    void find_absentProperty_isEmpty() {
        var properties = MetadataProperties.of("tenant", "acme");

        assertThat(properties.find("tenant")).contains("acme");
        assertThat(properties.find("region")).isEmpty();
        assertThat(properties.get("region")).isNull();
    }

    @Test
    void with_existingName_replacesItsValue() {
        var properties = MetadataProperties.of("tenant", "acme", "region", "eu");

        var replaced = properties.with(MetadataProperties.of("tenant", "globex"));

        assertThat(replaced.asMap()).containsOnly(Map.entry("tenant", "globex"), Map.entry("region", "eu"));
        assertThat(properties.get("tenant")).isEqualTo("acme");
    }

    @Test
    void without_names_removesOnlyThoseNames() {
        var properties = MetadataProperties.of("traceparent", "00-trace", "tracestate", "state", "tenant", "acme");

        var remaining = properties.without(List.of("traceparent", "tracestate"));

        assertThat(remaining.asMap()).containsOnly(Map.entry("tenant", "acme"));
    }

    @Test
    void inheritedBy_inheritance_keepsInheritedProperties() {
        var properties = MetadataProperties.of("tenant", "acme", "smd.processingId", "processing-1");

        var inherited = properties.inheritedBy(excludingSmdProperties());

        assertThat(inherited.names()).containsOnly("tenant");
    }

    @Test
    void metadataAnd_propertiesOfTheOtherMetadata_win() {
        var metadata = new Metadata(null, null, null, Map.of("tenant", "acme", "region", "eu"));

        var combined = metadata.and(Metadata.withProperty("tenant", "globex"));

        assertThat(combined.property("tenant")).contains("globex");
        assertThat(combined.property("region")).contains("eu");
    }
}
