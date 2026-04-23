package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static java.lang.Thread.sleep;

@Component
public class CreateAccountCommandHandler {

    private final EventPublisher eventPublisher;
    private final MetadataRecorder metadataRecorder;

    public CreateAccountCommandHandler(EventPublisher eventPublisher, MetadataRecorder metadataRecorder) {
        this.eventPublisher = eventPublisher;
        this.metadataRecorder = metadataRecorder;
    }

    @CommandHandler
    public UUID handle(CreateAccountCommand command, Metadata metadata, @MetadataValue("key") String value) throws InterruptedException {
        // We sleep to force a different timestamp for the AccountCreatedEvent
        sleep(100);
        metadataRecorder.recordCommand(metadata, value);
        eventPublisher.publish(new AccountCreatedEvent(command.name()));
        return UUID.randomUUID();
    }
}
