package app.dodb.smd.api.metadata;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static app.dodb.smd.api.metadata.MetadataTestConstants.PRINCIPAL;
import static app.dodb.smd.api.metadata.MetadataTestConstants.TIMESTAMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MetadataTest {

    @Test
    void build_withoutTimestamp_failsNamingTheTimestamp() {
        var builder = Metadata.builder().principal(PRINCIPAL);

        assertThatIllegalStateException()
            .isThrownBy(builder::build)
            .withMessageContaining("timestamp");
    }

    @Test
    void property_nameInPropertiesAndTransientProperties_returnsTransientValue() {
        var metadata = METADATA.withProperty("smd.errorCount", "0").withTransientProperty("smd.errorCount", "2");

        var errorCount = metadata.property("smd.errorCount");

        assertThat(errorCount).contains("2");
    }

    @Test
    void property_nameOnlyInProperties_returnsPropertyValue() {
        var metadata = METADATA.withProperty("tenant", "acme");

        var tenant = metadata.property("tenant");

        assertThat(tenant).contains("acme");
    }

    @Test
    void withProperties_clashingName_otherPropertiesWin() {
        var metadata = METADATA.withProperty("tenant", "acme").withProperty("region", "eu");

        var combined = metadata.withProperties(MetadataProperties.of(Map.of("tenant", "globex")));

        assertThat(combined.properties().asMap()).containsOnly(Map.entry("tenant", "globex"), Map.entry("region", "eu"));
    }

    @Test
    void withoutProperties_names_removesOnlyThoseProperties() {
        var metadata = METADATA.withProperty("tenant", "acme").withProperty("Idempotency-Key", "key-1").withTransientProperty("Idempotency-Key", "kept");

        var remaining = metadata.withoutProperties("Idempotency-Key");

        assertThat(remaining.properties().asMap()).containsOnly(Map.entry("tenant", "acme"));
        assertThat(remaining.transientProperties().asMap()).containsOnly(Map.entry("Idempotency-Key", "kept"));
    }

    @Test
    void withTransientProperty_keepsPropertiesAndMessageFacts() {
        var metadata = METADATA.withProperty("tenant", "acme");

        var withTransient = metadata.withTransientProperty("smd.processingId", "processing-1");

        assertThat(withTransient.properties()).isEqualTo(metadata.properties());
        assertThat(withTransient.transientProperties().asMap()).containsOnly(Map.entry("smd.processingId", "processing-1"));
        assertThat(withTransient.principal()).isEqualTo(PRINCIPAL);
        assertThat(withTransient.timestamp()).isEqualTo(TIMESTAMP);
    }
}
