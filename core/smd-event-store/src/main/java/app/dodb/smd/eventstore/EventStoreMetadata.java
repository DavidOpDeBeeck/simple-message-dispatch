package app.dodb.smd.eventstore;

import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataProperties;

import java.util.Optional;

public final class EventStoreMetadata {

    public static final String GLOBAL_POSITION = "smd.globalPosition";
    public static final String SUBJECT_VERSION = "smd.subjectVersion";

    private EventStoreMetadata() {
    }

    public static Optional<Long> subjectVersion(Metadata metadata) {
        return metadata.transientProperties().find(SUBJECT_VERSION)
            .map(EventStoreMetadata::parseSubjectVersion);
    }

    public static Metadata withSubjectVersion(Metadata metadata, long subjectVersion) {
        return metadata.withTransientProperties(MetadataProperties.builder()
            .with(SUBJECT_VERSION, String.valueOf(subjectVersion))
            .build());
    }

    private static long parseSubjectVersion(String subjectVersion) {
        try {
            return Long.parseLong(subjectVersion);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("""
                Invalid subject version metadata: %s must be a whole number.
                
                    Found:
                    %s
                """.formatted(SUBJECT_VERSION, subjectVersion), e);
        }
    }
}
