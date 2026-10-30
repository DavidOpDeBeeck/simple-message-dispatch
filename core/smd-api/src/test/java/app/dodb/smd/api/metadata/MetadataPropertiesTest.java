package app.dodb.smd.api.metadata;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.dodb.smd.api.metadata.MetadataInheritance.excludingSmdProperties;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataPropertiesTest {

    @Test
    void of_map_isNotChangedByLaterChangesToTheMap() {
        var source = new HashMap<>(Map.of("tenant", "acme"));
        var properties = MetadataProperties.of(source);

        source.put("tenant", "other");

        assertThat(properties.get("tenant")).isEqualTo("acme");
    }

    @Test
    void find_absentProperty_isEmpty() {
        var properties = MetadataProperties.of(Map.of("tenant", "acme"));

        assertThat(properties.find("tenant")).contains("acme");
        assertThat(properties.find("region")).isEmpty();
        assertThat(properties.get("region")).isNull();
    }

    @Test
    void with_existingName_replacesItsValue() {
        var properties = MetadataProperties.of(Map.of("tenant", "acme", "region", "eu"));

        var replaced = properties.with(MetadataProperties.of(Map.of("tenant", "globex")));

        assertThat(replaced.asMap()).containsOnly(Map.entry("tenant", "globex"), Map.entry("region", "eu"));
        assertThat(properties.get("tenant")).isEqualTo("acme");
    }

    @Test
    void builder_presentOptionalValue_addsTheProperty() {
        var properties = MetadataProperties.builder()
            .with("tenant", "acme")
            .with("region", Optional.of("eu"))
            .build();

        assertThat(properties.asMap()).containsOnly(Map.entry("tenant", "acme"), Map.entry("region", "eu"));
    }

    @Test
    void builder_emptyOptionalValue_skipsTheProperty() {
        var properties = MetadataProperties.builder()
            .with("tenant", "acme")
            .with("region", Optional.empty())
            .build();

        assertThat(properties.asMap()).containsOnly(Map.entry("tenant", "acme"));
    }

    @Test
    void without_names_removesOnlyThoseNames() {
        var properties = MetadataProperties.of(Map.of("traceparent", "00-trace", "tracestate", "state", "tenant", "acme"));

        var remaining = properties.without(List.of("traceparent", "tracestate"));

        assertThat(remaining.asMap()).containsOnly(Map.entry("tenant", "acme"));
    }

    @Test
    void inheritedBy_inheritance_keepsInheritedProperties() {
        var properties = MetadataProperties.of(Map.of("tenant", "acme", "smd.processingId", "processing-1"));

        var inherited = properties.inheritedBy(excludingSmdProperties());

        assertThat(inherited.names()).containsOnly("tenant");
    }
}
