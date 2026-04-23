package app.dodb.smd.spring.metadata;

import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.api.query.bus.QueryBus;
import app.dodb.smd.spring.metadata.example.AccountCreatedEvent;
import app.dodb.smd.spring.metadata.example.CreateAccountCommand;
import app.dodb.smd.spring.metadata.example.GetAccountBalanceQuery;
import app.dodb.smd.spring.metadata.example.MetadataRecorder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.WebApplicationType.NONE;

public class MetadataIntegrationTest {

    @ParameterizedTest(name = "command metadata with {0}")
    @ValueSource(classes = {
        MetadataIntegrationTestConfiguration.class,
        MetadataIntegrationTestConfigurationWithAsyncAwait.class,
        MetadataIntegrationTestConfigurationWithAsyncFireAndForget.class
    })
    void commandMetadataIsPreserved(Class<?> configClass) {
        try (var context = createContext(configClass)) {
            var commandBus = context.getBean(CommandBus.class);
            var metadataRecorder = context.getBean(MetadataRecorder.class);
            var initialTimestamp = Instant.now();
            var initialPrincipal = SimplePrincipal.create();
            commandBus.send(CommandMessage.from(
                new CreateAccountCommand("ACCOUNT_NAME"),
                new Metadata(initialPrincipal, initialTimestamp, null, Map.of("key", "value")))
            );

            await().untilAsserted(() -> {
                assertThat(metadataRecorder.commandMetadata()).hasSize(1);
            });

            assertPrincipalToEqual(metadataRecorder.commandMetadata(), initialPrincipal);
            assertTimestampToEqual(metadataRecorder.commandMetadata(), initialTimestamp);
            assertThat(metadataRecorder.commandMetadataValues()).containsExactly("value");
        }
    }

    @ParameterizedTest(name = "query metadata with {0}")
    @ValueSource(classes = {
        MetadataIntegrationTestConfiguration.class,
        MetadataIntegrationTestConfigurationWithAsyncAwait.class,
        MetadataIntegrationTestConfigurationWithAsyncFireAndForget.class
    })
    void queryMetadataIsPreserved(Class<?> configClass) {
        try (var context = createContext(configClass)) {
            var queryBus = context.getBean(QueryBus.class);
            var metadataRecorder = context.getBean(MetadataRecorder.class);
            var initialTimestamp = Instant.now();
            var initialPrincipal = SimplePrincipal.create();

            queryBus.send(QueryMessage.from(
                new GetAccountBalanceQuery(),
                new Metadata(initialPrincipal, initialTimestamp, null, Map.of("key", "value"))
            ));

            await().untilAsserted(() -> {
                assertThat(metadataRecorder.queryMetadata()).hasSize(1);
            });

            assertPrincipalToEqual(metadataRecorder.queryMetadata(), initialPrincipal);
            assertTimestampToEqual(metadataRecorder.queryMetadata(), initialTimestamp);
            assertThat(metadataRecorder.queryMetadataValues()).containsExactly("value");
        }
    }

    @ParameterizedTest(name = "event metadata with {0}")
    @ValueSource(classes = {
        MetadataIntegrationTestConfiguration.class,
        MetadataIntegrationTestConfigurationWithAsyncAwait.class,
        MetadataIntegrationTestConfigurationWithAsyncFireAndForget.class
    })
    void nestedEventDispatchInheritsParentEventMetadata(Class<?> configClass) {
        try (var context = createContext(configClass)) {
            var eventPublisher = context.getBean(EventPublisher.class);
            var metadataRecorder = context.getBean(MetadataRecorder.class);
            var initialTimestamp = Instant.now();
            var initialPrincipal = SimplePrincipal.create();
            var eventMetadata = new Metadata(initialPrincipal, initialTimestamp, null, Map.of("key", "value"));
            var eventMessage = EventMessage.from(new AccountCreatedEvent("ACCOUNT_NAME"), eventMetadata);

            eventPublisher.publish(eventMessage);

            await().untilAsserted(() -> {
                assertThat(metadataRecorder.accountCreatedEventMetadata()).hasSize(1);
                assertThat(metadataRecorder.accountCreatedEventMessageIds()).hasSize(1);
                assertThat(metadataRecorder.nestedQueryMetadata()).hasSize(1);
                assertThat(metadataRecorder.nestedEventMetadata()).hasSize(1);
            });

            var accountCreatedEventMetadata = metadataRecorder.accountCreatedEventMetadata();
            var accountCreatedEventMessageIds = metadataRecorder.accountCreatedEventMessageIds();
            var nestedQueryMetadata = metadataRecorder.nestedQueryMetadata();
            var nestedEventMetadata = metadataRecorder.nestedEventMetadata();

            assertPrincipalToEqual(accountCreatedEventMetadata, initialPrincipal);
            assertTimestampToEqual(accountCreatedEventMetadata, initialTimestamp);
            assertPrincipalToEqual(nestedQueryMetadata, initialPrincipal);
            assertTimestampToNotEqual(nestedQueryMetadata, initialTimestamp);
            assertThat(nestedQueryMetadata)
                .extracting(Metadata::parentMessageId)
                .containsExactly(accountCreatedEventMessageIds.getFirst());

            assertPrincipalToEqual(nestedEventMetadata, initialPrincipal);
            assertTimestampToNotEqual(nestedEventMetadata, initialTimestamp);
            assertThat(nestedEventMetadata)
                .extracting(Metadata::parentMessageId)
                .containsExactly(accountCreatedEventMessageIds.getFirst());

            assertThat(metadataRecorder.accountCreatedEventValues()).containsExactly("value");
            assertThat(metadataRecorder.nestedQueryMetadataValues()).containsExactly("value");
            assertThat(metadataRecorder.nestedEventMetadataValues()).containsExactly("value");
        }
    }

    private ConfigurableApplicationContext createContext(Class<?> configClass) {
        return new SpringApplicationBuilder(configClass)
            .web(NONE)
            .run();
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
