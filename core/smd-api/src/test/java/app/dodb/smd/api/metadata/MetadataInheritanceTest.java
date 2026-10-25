package app.dodb.smd.api.metadata;

import org.junit.jupiter.api.Test;

import static app.dodb.smd.api.metadata.MetadataInheritance.excludingPrefix;
import static app.dodb.smd.api.metadata.MetadataInheritance.excludingProperties;
import static app.dodb.smd.api.metadata.MetadataInheritance.excludingSmdProperties;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataInheritanceTest {

    @Test
    void excludingSmdProperties_smdProperty_isNotInherited() {
        var inherited = excludingSmdProperties().inherits("smd.processingId");

        assertThat(inherited).isFalse();
    }

    @Test
    void excludingSmdProperties_applicationProperty_isInherited() {
        var inherited = excludingSmdProperties().inherits("tenant");

        assertThat(inherited).isTrue();
    }

    @Test
    void excludingPrefix_propertyWithPrefix_isNotInherited() {
        var inheritance = excludingPrefix("local.");

        assertThat(inheritance.inherits("local.requestId")).isFalse();
        assertThat(inheritance.inherits("tenant")).isTrue();
    }

    @Test
    void excludingProperties_listedProperty_isNotInherited() {
        var inheritance = excludingProperties("Idempotency-Key");

        assertThat(inheritance.inherits("Idempotency-Key")).isFalse();
        assertThat(inheritance.inherits("tenant")).isTrue();
    }

    @Test
    void and_eitherExcludes_propertyIsNotInherited() {
        var inheritance = excludingSmdProperties().and(excludingProperties("Idempotency-Key"));

        assertThat(inheritance.inherits("smd.position")).isFalse();
        assertThat(inheritance.inherits("Idempotency-Key")).isFalse();
        assertThat(inheritance.inherits("tenant")).isTrue();
    }
}
