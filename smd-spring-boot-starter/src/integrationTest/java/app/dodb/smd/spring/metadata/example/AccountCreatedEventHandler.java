package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ProcessingGroup
public class AccountCreatedEventHandler {

    public static final List<Metadata> handledMetadata = new ArrayList<>();

    @EventHandler
    public void handle(AccountCreatedEvent event, Metadata metadata) {
        handledMetadata.add(metadata);
    }
}
