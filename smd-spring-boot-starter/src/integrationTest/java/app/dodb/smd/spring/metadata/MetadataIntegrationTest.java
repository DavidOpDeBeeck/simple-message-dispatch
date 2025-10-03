package app.dodb.smd.spring.metadata;

import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.spring.metadata.example.AccountCreatedEventHandler;
import app.dodb.smd.spring.metadata.example.CreateAccountCommand;
import app.dodb.smd.spring.metadata.example.CreateAccountCommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@SpringBootTest(classes = MetadataIntegrationTestConfiguration.class, webEnvironment = NONE)
public class MetadataIntegrationTest {

    @Autowired
    private CommandBus commandBus;

    @Test
    void principalIsCopiedFromCommandToEvent() {
        var commandTimestamp = LocalDateTime.now();
        var commandPrincipal = SimplePrincipal.create();
        commandBus.send(CommandMessage.from(new CreateAccountCommand("ACCOUNT_NAME"), new Metadata(commandPrincipal, commandTimestamp)));

        List<Metadata> commandMetadata = CreateAccountCommandHandler.handledMetadata;
        List<Metadata> eventMetadata = AccountCreatedEventHandler.handledMetadata;

        assertPrincipalToEqual(commandMetadata, commandPrincipal);
        assertTimestampToEqual(commandMetadata, commandTimestamp);

        assertPrincipalToEqual(eventMetadata, commandPrincipal);
        assertTimestampToNotEqual(eventMetadata, commandTimestamp);
    }

    private static void assertPrincipalToEqual(List<Metadata> metadata, SimplePrincipal principal) {
        assertThat(metadata)
            .extracting(Metadata::principal)
            .containsExactly(principal);
    }

    private static void assertTimestampToEqual(List<Metadata> metadata, LocalDateTime timestamp) {
        assertThat(metadata)
            .extracting(Metadata::timestamp)
            .containsExactly(timestamp);
    }

    private static void assertTimestampToNotEqual(List<Metadata> metadata, LocalDateTime timestamp) {
        assertThat(metadata)
            .extracting(Metadata::timestamp)
            .doesNotContain(timestamp);
    }
}
