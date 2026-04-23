package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.query.QueryHandler;
import org.springframework.stereotype.Component;

@Component
public class LookupAccountAuditQueryHandler {

    private final MetadataRecorder metadataRecorder;

    public LookupAccountAuditQueryHandler(MetadataRecorder metadataRecorder) {
        this.metadataRecorder = metadataRecorder;
    }

    @QueryHandler
    public int handle(LookupAccountAuditQuery query, Metadata metadata, @MetadataValue("key") String value) {
        metadataRecorder.recordNestedQuery(metadata, value);
        return 1;
    }
}
