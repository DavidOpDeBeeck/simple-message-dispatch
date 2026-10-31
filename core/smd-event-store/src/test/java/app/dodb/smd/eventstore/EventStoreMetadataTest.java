package app.dodb.smd.eventstore;

import app.dodb.smd.api.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static app.dodb.smd.eventstore.EventStoreMetadata.SUBJECT_VERSION;
import static app.dodb.smd.eventstore.EventStoreMetadata.subjectVersion;
import static app.dodb.smd.eventstore.EventStoreMetadata.withSubjectVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStoreMetadataTest {

    private static final Metadata METADATA = Metadata.at(Instant.parse("2026-04-23T10:15:30Z"));

    @Test
    void withSubjectVersion_setsVersionAsTransientProperty() {
        var metadata = withSubjectVersion(METADATA, 3L);

        assertThat(metadata.transientProperties().asMap()).containsExactlyEntriesOf(Map.of(SUBJECT_VERSION, "3"));
        assertThat(metadata.properties().isEmpty()).isTrue();
    }

    @Test
    void subjectVersion_metadataWithVersion_returnsVersion() {
        var metadata = withSubjectVersion(METADATA, 3L);

        assertThat(subjectVersion(metadata)).contains(3L);
    }

    @Test
    void subjectVersion_noVersion_returnsEmpty() {
        assertThat(subjectVersion(METADATA)).isEmpty();
    }

    @Test
    void subjectVersion_versionAsStoredProperty_returnsEmpty() {
        var metadata = METADATA.withProperty(SUBJECT_VERSION, "3");

        assertThat(subjectVersion(metadata)).isEmpty();
    }

    @Test
    void subjectVersion_nonNumericVersion_failsWithVersion() {
        var metadata = METADATA.withTransientProperty(SUBJECT_VERSION, "three");

        assertThatThrownBy(() -> subjectVersion(metadata))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(SUBJECT_VERSION + " must be a whole number")
            .hasMessageContaining("three")
            .hasCauseInstanceOf(NumberFormatException.class);
    }
}
