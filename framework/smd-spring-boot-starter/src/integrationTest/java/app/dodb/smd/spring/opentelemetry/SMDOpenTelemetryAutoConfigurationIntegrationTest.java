package app.dodb.smd.spring.opentelemetry;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandler;
import app.dodb.smd.eventstore.EventStoreConfig;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.serialization.JacksonEventSerializer;
import app.dodb.smd.eventstore.serialization.SMDJacksonModule;
import app.dodb.smd.opentelemetry.MessageSpans;
import app.dodb.smd.opentelemetry.MessageTracing;
import app.dodb.smd.spring.EnableSMD;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;

import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SMDOpenTelemetryAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(BusConfiguration.class);
    private final ApplicationContextRunner traced = runner.withUserConfiguration(OpenTelemetryConfiguration.class);

    @Test
    void send_withOpenTelemetry_recordsEachDispatchAndEventHandlingLinkedToTheInvocationItContinues() {
        traced.run(context -> {
            // Given
            var commands = context.getBean(CommandGateway.class);
            var exporter = context.getBean(InMemorySpanExporter.class);

            // When
            commands.send(new SampleCommand());

            // Then
            var spans = exporter.getFinishedSpanItems();
            assertThat(spans).hasSize(4);
            var command = span(spans, "send", SampleCommand.class);
            var publication = span(spans, "publish", SampleEvent.class);
            var sending = span(spans, "send", SampleEvent.class);
            var eventHandling = span(spans, "process", SampleEvent.class);
            assertThat(command.getLinks()).isEmpty();
            assertChildLinkedTo(publication, command);
            assertChildLinkedTo(sending, publication);
            assertThat(eventHandling.getParentSpanId()).isEqualTo(sending.getSpanId());
            assertLinksTo(eventHandling, publication);
            assertThat(eventHandling.getAttributes().get(MessageSpans.PROCESSING_GROUP)).isEqualTo("tracing-test");
            assertThat(command.getAttributes().get(MessageSpans.PROCESSING_GROUP)).isNull();
            assertThat(sending.getAttributes().get(MessageSpans.DELIVERY)).isEqualTo("sync");
        });
    }

    @Test
    void send_queryWithinASpanOfTheHandler_isAChildOfThatSpanLinkedToTheCommandsDispatch() {
        traced.run(context -> {
            // Given
            var commands = context.getBean(CommandGateway.class);
            var exporter = context.getBean(InMemorySpanExporter.class);

            // When
            commands.send(new ShipOrderCommand());

            // Then
            var spans = exporter.getFinishedSpanItems();
            var command = span(spans, "send", ShipOrderCommand.class);
            var reservation = spans.stream().filter(span -> span.getName().equals("reserve-stock")).findFirst().orElseThrow();
            var query = span(spans, "send", StockLevelQuery.class);
            assertThat(reservation.getParentSpanId()).isEqualTo(command.getSpanId());
            assertThat(query.getParentSpanId()).isEqualTo(reservation.getSpanId());
            assertLinksTo(query, command);
        });
    }

    @Test
    void publish_commandSentByAnEventHandler_isAChildOfTheHandlingLinkedToIt() {
        traced.run(context -> {
            // Given
            var events = context.getBean(EventPublisher.class);
            var exporter = context.getBean(InMemorySpanExporter.class);

            // When
            events.publish(new PaymentReceivedEvent());

            // Then
            var spans = exporter.getFinishedSpanItems();
            var handling = span(spans, "process", PaymentReceivedEvent.class);
            var command = span(spans, "send", ConfirmOrderCommand.class);
            assertChildLinkedTo(command, handling);
        });
    }

    @Test
    void publish_handlerDeferringWorkThatFails_recordsTheFailureOnThePublicationSpanThatOwnsTheTransaction() {
        traced.run(context -> {
            // Given
            var events = context.getBean(EventPublisher.class);
            var exporter = context.getBean(InMemorySpanExporter.class);

            // When
            assertThatThrownBy(() -> events.publish(new DeferringEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("deferred work failed");

            // Then
            var spans = exporter.getFinishedSpanItems();
            var publication = span(spans, "publish", DeferringEvent.class);
            var handling = span(spans, "process", DeferringEvent.class);
            assertThat(publication.getStatus().getStatusCode()).isEqualTo(ERROR);
            assertThat(publication.getAttributes().get(MessageSpans.ERROR_TYPE)).isEqualTo(IllegalStateException.class.getName());
            assertThat(handling.getStatus().getStatusCode()).isNotEqualTo(ERROR);
        });
    }

    @Test
    void configure_withEventStore_installsMessageTracingAsEventStoreSinkInterceptor() {
        // Given / When / Then
        traced.withUserConfiguration(EventStoreConfiguration.class)
            .withPropertyValues("smd.event-store.enabled=true", "smd.event-store.scheduling.enabled=false")
            .run(context -> assertThat(context.getBean(EventStoreConfig.class).getSinkInterceptors())
                .containsExactly(context.getBean(MessageTracing.class)));
    }

    @Test
    void configure_withEventStoreWithoutOpenTelemetry_installsNoEventStoreSinkInterceptor() {
        // Given / When / Then
        runner.withUserConfiguration(EventStoreConfiguration.class)
            .withPropertyValues("smd.event-store.enabled=true", "smd.event-store.scheduling.enabled=false")
            .run(context -> assertThat(context.getBean(EventStoreConfig.class).getSinkInterceptors()).isEmpty());
    }

    @Test
    void configure_withoutOpenTelemetry_registersNoMessageTracing() {
        // Given / When / Then
        runner.run(context -> assertThat(context)
            .hasNotFailed()
            .doesNotHaveBean(MessageTracing.class));
    }

    @Test
    void configure_disabled_registersNoMessageTracing() {
        // Given / When / Then
        traced.withPropertyValues("smd.opentelemetry.enabled=false")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(MessageTracing.class));
    }

    private static SpanData span(List<SpanData> spans, String operation, Class<?> type) {
        return spans.stream().filter(span -> span.getName().equals(operation + " " + type.getName())).findFirst().orElseThrow();
    }

    private static void assertChildLinkedTo(SpanData span, SpanData parent) {
        assertThat(span.getParentSpanId()).isEqualTo(parent.getSpanId());
        assertLinksTo(span, parent);
    }

    private static void assertLinksTo(SpanData span, SpanData target) {
        assertThat(span.getLinks()).singleElement().satisfies(link -> assertThat(link.getSpanContext().getSpanId()).isEqualTo(target.getSpanId()));
    }

    @Configuration(proxyBeanMethods = false)
    static class OpenTelemetryConfiguration {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        OpenTelemetry openTelemetry(InMemorySpanExporter spanExporter) {
            return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)).build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EventStoreConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:postgresql://localhost/unused");
        }

        @Bean
        EventSerializer eventSerializer() {
            return new JacksonEventSerializer(JsonMapper.builder().addModule(new SMDJacksonModule()).build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSMD(packages = "app.dodb.smd.spring.opentelemetry")
    static class BusConfiguration {

        @Bean
        TestHandlers testHandlers(EventPublisher events,
                                  QueryGateway queries,
                                  CommandGateway commands,
                                  TransactionProvider transactions,
                                  ObjectProvider<OpenTelemetry> openTelemetry) {
            return new TestHandlers(events, queries, commands, transactions, openTelemetry);
        }
    }

    public static class TestHandlers {

        private final EventPublisher events;
        private final QueryGateway queries;
        private final CommandGateway commands;
        private final TransactionProvider transactions;
        private final ObjectProvider<OpenTelemetry> openTelemetry;

        public TestHandlers(EventPublisher events,
                            QueryGateway queries,
                            CommandGateway commands,
                            TransactionProvider transactions,
                            ObjectProvider<OpenTelemetry> openTelemetry) {
            this.events = events;
            this.queries = queries;
            this.commands = commands;
            this.transactions = transactions;
            this.openTelemetry = openTelemetry;
        }

        @CommandHandler
        public String handle(SampleCommand command) {
            events.publish(new SampleEvent());
            return "opened";
        }

        @CommandHandler
        public String handle(ShipOrderCommand command) {
            var reservation = openTelemetry.getObject().getTracer("application").spanBuilder("reserve-stock").startSpan();
            try (var ignored = reservation.makeCurrent()) {
                return queries.send(new StockLevelQuery());
            } finally {
                reservation.end();
            }
        }

        @CommandHandler
        public String handle(ConfirmOrderCommand command) {
            return "confirmed";
        }

        @QueryHandler
        public String handle(StockLevelQuery query) {
            return "in-stock";
        }

        @EventHandler
        @ProcessingGroup("tracing-test")
        public void handle(SampleEvent event) {
        }

        @EventHandler
        @ProcessingGroup("tracing-test")
        public void handle(PaymentReceivedEvent event) {
            commands.send(new ConfirmOrderCommand());
        }

        // Synchronous handlers join the publisher's transaction, so the deferred work runs when that transaction ends, after the handling span.
        @EventHandler
        @ProcessingGroup("tracing-test")
        public void handle(DeferringEvent event) {
            transactions.defer(() -> {
                throw new IllegalStateException("deferred work failed");
            });
        }
    }

    public record SampleCommand() implements Command<String> {
    }

    public record SampleEvent() implements Event {
    }

    public record ShipOrderCommand() implements Command<String> {
    }

    public record ConfirmOrderCommand() implements Command<String> {
    }

    public record StockLevelQuery() implements Query<String> {
    }

    public record PaymentReceivedEvent() implements Event {
    }

    public record DeferringEvent() implements Event {
    }
}
