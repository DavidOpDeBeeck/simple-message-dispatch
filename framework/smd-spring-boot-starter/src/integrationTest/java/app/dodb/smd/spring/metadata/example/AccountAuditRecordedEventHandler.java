package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import org.springframework.stereotype.Component;

@Component
@ProcessingGroup
public class AccountAuditRecordedEventHandler {

    private final MetadataRecorder metadataRecorder;

    public AccountAuditRecordedEventHandler(MetadataRecorder metadataRecorder) {
        this.metadataRecorder = metadataRecorder;
    }

    @EventHandler
    public void handle(AccountAuditRecordedEvent event, Metadata metadata, @MetadataValue("key") String value) {
        metadataRecorder.recordNestedEvent(metadata, value);
    }
}
