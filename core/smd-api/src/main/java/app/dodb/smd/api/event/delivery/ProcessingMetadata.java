package app.dodb.smd.api.event.delivery;

import static app.dodb.smd.api.metadata.MetadataInheritance.SMD_PREFIX;

public final class ProcessingMetadata {

    public static final String PROCESSING_ID = SMD_PREFIX + "processingId";
    public static final String SEQUENCE_NUMBER = SMD_PREFIX + "sequenceNumber";
    public static final String ERROR_COUNT = SMD_PREFIX + "errorCount";
    public static final String SUBJECT_ID = SMD_PREFIX + "subjectId";

    private ProcessingMetadata() {
    }
}
