package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.lang.Thread.sleep;

@Component
public class CreateAccountCommandHandler {

    public static final List<Metadata> handledMetadata = new ArrayList<>();

    private final EventPublisher eventPublisher;

    public CreateAccountCommandHandler(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @CommandHandler
    public UUID handle(CreateAccountCommand command, Metadata metadata) throws InterruptedException {
        // We sleep to force a different timestamp for the AccountCreatedEvent
        sleep(100);
        handledMetadata.add(metadata);
        eventPublisher.publish(new AccountCreatedEvent(command.name()));
        return UUID.randomUUID();
    }
}
