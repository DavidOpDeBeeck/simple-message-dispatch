package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.query.QueryGateway;
import org.springframework.stereotype.Component;

import static java.lang.Thread.sleep;

@Component
@ProcessingGroup
public class AccountCreatedEventHandler {

    private final QueryGateway queryGateway;
    private final EventPublisher eventPublisher;
    private final MetadataRecorder metadataRecorder;

    public AccountCreatedEventHandler(QueryGateway queryGateway,
                                      EventPublisher eventPublisher,
                                      MetadataRecorder metadataRecorder) {
        this.queryGateway = queryGateway;
        this.eventPublisher = eventPublisher;
        this.metadataRecorder = metadataRecorder;
    }

    @EventHandler
    public void handle(AccountCreatedEvent event, Metadata metadata, MessageId messageId, @MetadataValue("key") String value) throws InterruptedException {
        metadataRecorder.recordAccountCreatedEvent(metadata, messageId, value);
        // We sleep to force different timestamps for nested async dispatches.
        sleep(100);
        queryGateway.send(new LookupAccountAuditQuery());
        eventPublisher.publish(new AccountAuditRecordedEvent(event.name()));
    }
}
