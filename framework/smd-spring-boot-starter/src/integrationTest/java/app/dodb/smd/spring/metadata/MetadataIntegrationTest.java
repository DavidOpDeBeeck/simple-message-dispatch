package app.dodb.smd.spring.metadata;

import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.api.query.bus.QueryBus;
import app.dodb.smd.spring.metadata.example.AccountCreatedEventHandler;
import app.dodb.smd.spring.metadata.example.CreateAccountCommand;
import app.dodb.smd.spring.metadata.example.CreateAccountCommandHandler;
import app.dodb.smd.spring.metadata.example.GetAccountBalanceQuery;
import app.dodb.smd.spring.metadata.example.GetAccountBalanceQueryHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@SpringBootTest(classes = MetadataIntegrationTestConfiguration.class, webEnvironment = NONE)
public class MetadataIntegrationTest {

    @Autowired
    private CommandBus commandBus;
    @Autowired
    private QueryBus queryBus;

    @Test
    void principalIsCopiedFromCommandToEvent() {
        var initialTimestamp = Instant.now();
        var initialPrincipal = SimplePrincipal.create();
        commandBus.send(CommandMessage.from(
            new CreateAccountCommand("ACCOUNT_NAME"),
            new Metadata(initialPrincipal, initialTimestamp, null, Map.of("key", "value")))
        );
        queryBus.send(QueryMessage.from(
            new GetAccountBalanceQuery(),
            new Metadata(initialPrincipal, initialTimestamp, null, Map.of("key", "value"))
        ));

        List<Metadata> commandMetadata = CreateAccountCommandHandler.handledMetadata;
        List<Metadata> eventMetadata = AccountCreatedEventHandler.handledMetadata;
        List<Metadata> queryMetadata = GetAccountBalanceQueryHandler.handledMetadata;

        assertPrincipalToEqual(commandMetadata, initialPrincipal);
        assertTimestampToEqual(commandMetadata, initialTimestamp);

        assertPrincipalToEqual(eventMetadata, initialPrincipal);
        assertTimestampToNotEqual(eventMetadata, initialTimestamp);

        assertPrincipalToEqual(queryMetadata, initialPrincipal);
        assertTimestampToEqual(queryMetadata, initialTimestamp);

        var commandMetadataValues = CreateAccountCommandHandler.metadataValues;
        var eventMetadataValues = AccountCreatedEventHandler.metadataValues;
        var queryMetadataValues = GetAccountBalanceQueryHandler.metadataValues;

        assertThat(commandMetadataValues).containsExactly("value");
        assertThat(eventMetadataValues).containsExactly("value");
        assertThat(queryMetadataValues).containsExactly("value");
    }

    private static void assertPrincipalToEqual(List<Metadata> metadata, SimplePrincipal principal) {
        assertThat(metadata)
            .extracting(Metadata::principal)
            .containsExactly(principal);
    }

    private static void assertTimestampToEqual(List<Metadata> metadata, Instant timestamp) {
        assertThat(metadata)
            .extracting(Metadata::timestamp)
            .containsExactly(timestamp);
    }

    private static void assertTimestampToNotEqual(List<Metadata> metadata, Instant timestamp) {
        assertThat(metadata)
            .extracting(Metadata::timestamp)
            .doesNotContain(timestamp);
    }
}
