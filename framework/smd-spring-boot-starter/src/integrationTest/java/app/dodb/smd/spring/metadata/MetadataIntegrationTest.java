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
import app.dodb.smd.spring.metadata.example.MetadataRecorder.RecordedEventMetadata;
import app.dodb.smd.spring.metadata.example.MetadataRecorder.RecordedMetadata;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

class MetadataIntegrationTest {

    private static final Instant INITIAL_TIMESTAMP = Instant.parse("2020-01-01T00:00:00Z");

    @ParameterizedTest(name = "command metadata with {0}")
    @ValueSource(classes = {
        MetadataIntegrationTestConfiguration.class,
        MetadataIntegrationTestConfigurationWithAsyncAwait.class,
        MetadataIntegrationTestConfigurationWithAsyncFireAndForget.class
    })
    void send_withCommandMetadata_preservesMetadataAndEventLineage(Class<?> configClass) throws InterruptedException {
        // Given
        try (var context = createContext(configClass)) {
            var commandBus = context.getBean(CommandBus.class);
            var recorder = context.getBean(MetadataRecorder.class);
            var metadata = new Metadata(SimplePrincipal.create(), INITIAL_TIMESTAMP, null, Map.of("key", "value"));
            var command = CommandMessage.from(new CreateAccountCommand("ACCOUNT_NAME"), metadata);

            // When
            commandBus.send(command);

            // Then
            assertThat(recorder.commands()).containsExactly(new RecordedMetadata(metadata, "value"));

            recorder.awaitNestedEvent();
            assertThat(recorder.accountCreatedEvents()).singleElement().satisfies(event -> {
                assertThat(event.metadata().parentMessageId()).isEqualTo(command.messageId());
                assertThat(event.metadata().principal()).isEqualTo(metadata.principal());
                assertThat(event.metadata().timestamp()).isAfter(INITIAL_TIMESTAMP);
                assertThat(event.metadata().properties()).isEqualTo(metadata.properties());
                assertThat(event.value()).isEqualTo("value");
            });
        }
    }

    @ParameterizedTest(name = "query metadata with {0}")
    @ValueSource(classes = {
        MetadataIntegrationTestConfiguration.class,
        MetadataIntegrationTestConfigurationWithAsyncAwait.class,
        MetadataIntegrationTestConfigurationWithAsyncFireAndForget.class
    })
    void send_withQueryMetadata_preservesMetadata(Class<?> configClass) {
        // Given
        try (var context = createContext(configClass)) {
            var queryBus = context.getBean(QueryBus.class);
            var recorder = context.getBean(MetadataRecorder.class);
            var metadata = new Metadata(SimplePrincipal.create(), INITIAL_TIMESTAMP, null, Map.of("key", "value"));
            var query = QueryMessage.from(new GetAccountBalanceQuery(), metadata);

            // When
            queryBus.send(query);

            // Then
            assertThat(recorder.queries()).containsExactly(new RecordedMetadata(metadata, "value"));
        }
    }

    @ParameterizedTest(name = "event metadata with {0}")
    @ValueSource(classes = {
        MetadataIntegrationTestConfiguration.class,
        MetadataIntegrationTestConfigurationWithAsyncAwait.class,
        MetadataIntegrationTestConfigurationWithAsyncFireAndForget.class
    })
    void publish_withNestedDispatch_inheritsParentEventMetadata(Class<?> configClass) throws InterruptedException {
        // Given
        try (var context = createContext(configClass)) {
            var eventPublisher = context.getBean(EventPublisher.class);
            var recorder = context.getBean(MetadataRecorder.class);
            var metadata = new Metadata(SimplePrincipal.create(), INITIAL_TIMESTAMP, null, Map.of("key", "value"));
            var event = EventMessage.from(new AccountCreatedEvent("ACCOUNT_NAME"), metadata);

            // When
            eventPublisher.publish(event);

            // Then
            recorder.awaitNestedEvent();
            assertThat(recorder.accountCreatedEvents())
                .containsExactly(new RecordedEventMetadata(event.messageId(), metadata, "value"));
            assertThat(recorder.nestedQueries()).singleElement().satisfies(query -> {
                assertThat(query.metadata().principal()).isEqualTo(metadata.principal());
                assertThat(query.metadata().timestamp()).isAfter(INITIAL_TIMESTAMP);
                assertThat(query.metadata().parentMessageId()).isEqualTo(event.messageId());
                assertThat(query.metadata().properties()).isEqualTo(metadata.properties());
                assertThat(query.value()).isEqualTo("value");
            });
            assertThat(recorder.nestedEvents()).singleElement().satisfies(nestedEvent -> {
                assertThat(nestedEvent.metadata().principal()).isEqualTo(metadata.principal());
                assertThat(nestedEvent.metadata().timestamp()).isAfter(INITIAL_TIMESTAMP);
                assertThat(nestedEvent.metadata().parentMessageId()).isEqualTo(event.messageId());
                assertThat(nestedEvent.metadata().properties()).isEqualTo(metadata.properties());
                assertThat(nestedEvent.value()).isEqualTo("value");
            });
        }
    }

    private ConfigurableApplicationContext createContext(Class<?> configClass) {
        return new SpringApplicationBuilder(configClass)
            .web(NONE)
            .run();
    }
}
