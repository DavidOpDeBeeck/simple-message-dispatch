package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.command.AnnotatedCommandHandler;
import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.metadata.Metadata;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static app.dodb.smd.opentelemetry.MessageSpans.DESTINATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.ERROR_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.INSTRUMENTATION_SCOPE;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGING_SYSTEM;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.THREAD_NAME;
import static app.dodb.smd.opentelemetry.SpanAssertions.linksTo;
import static app.dodb.smd.opentelemetry.SpanAssertions.traceparentOf;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandTracingTest {

    @RegisterExtension
    private final RecordedSpans spans = new RecordedSpans();
    private final CommandBus commandBus = CommandBusSpec.withDefaults()
        .interceptors(new MessageTracing(spans.openTelemetry()))
        .commandHandlers(() -> AnnotatedCommandHandler.from(new Handlers()))
        .create();

    @Test
    void send_command_returnsTheResultAndRecordsAProducerSendSpan() {
        var message = CommandMessage.from(new PlaceOrder("acme"), Metadata.now());

        var result = commandBus.send(message);

        assertThat(result).isEqualTo("accepted acme");
        assertThat(spans.span("send", PlaceOrder.class))
            .hasKind(PRODUCER)
            .hasInstrumentationScopeInfo(InstrumentationScopeInfo.create(INSTRUMENTATION_SCOPE))
            .hasNoParent()
            .hasTotalRecordedLinks(0)
            .hasStatus(StatusData.unset())
            .hasAttributesSatisfyingExactly(
                equalTo(MESSAGING_SYSTEM, "smd"),
                equalTo(OPERATION_TYPE, "send"),
                equalTo(OPERATION_NAME, "send"),
                equalTo(DESTINATION_NAME, PlaceOrder.class.getName()),
                equalTo(MESSAGE_ID, message.messageId().value().toString()),
                equalTo(MESSAGE_KIND, "command"),
                equalTo(THREAD_NAME, Thread.currentThread().getName()));
    }

    @Test
    void send_command_recordsNoPayloadMetadataOrResult() {
        commandBus.send(new PlaceOrder("sensitive-payload"), Metadata.now().withProperty("secret", "private-metadata"));

        assertThat(spans.span("send", PlaceOrder.class).getAttributes().asMap().values())
            .extracting(Object::toString)
            .noneMatch(value -> value.contains("sensitive-payload") || value.contains("private-metadata") || value.contains("accepted"));
    }

    @Test
    void send_failingHandler_rethrowsTheFailureAndRecordsOnlyItsClass() {
        var failure = new IllegalStateException("customer 42 is blocked");

        assertThatThrownBy(() -> commandBus.send(new FailingCommand(failure))).isSameAs(failure);

        assertThat(spans.span("send", FailingCommand.class))
            .hasStatus(StatusData.error())
            .hasAttribute(ERROR_TYPE, IllegalStateException.class.getName())
            .hasTotalRecordedEvents(0);
    }

    @Test
    void send_command_handlerReceivesMetadataCarryingTheSendSpan() {
        var metadata = Metadata.now().withProperty("tracestate", "stale=1").withProperty("tenant", "acme");

        var received = commandBus.send(new ReadMetadata(), metadata);

        assertThat(received.properties().asMap())
            .containsEntry("traceparent", traceparentOf(spans.span("send", ReadMetadata.class)))
            .containsEntry("tenant", "acme")
            .doesNotContainKey("tracestate");
    }

    @Test
    void send_commandFromAHandler_isAChildOfTheOuterSendAndLinksToIt() {
        commandBus.send(new OuterCommand(_ -> commandBus.send(new InnerCommand())));

        var outer = spans.span("send", OuterCommand.class);
        assertThat(spans.span("send", InnerCommand.class))
            .hasParent(outer)
            .hasLinksSatisfying(linksTo(outer));
    }

    @Test
    void send_commandWithinASpanOfTheApplication_isAChildOfThatSpanAndLinksToTheOuterSend() {
        commandBus.send(new OuterCommand(_ -> {
            var service = spans.startApplicationSpan("service");
            try (var _ = service.makeCurrent()) {
                return commandBus.send(new InnerCommand());
            } finally {
                service.end();
            }
        }));

        assertThat(spans.span("send", InnerCommand.class))
            .hasParent(spans.named("service"))
            .hasLinksSatisfying(linksTo(spans.span("send", OuterCommand.class)));
    }

    @Test
    void send_commandOnAnotherThreadWithTheHandlersMetadata_continuesTheOuterSend() {
        commandBus.send(new OuterCommand(metadata -> CompletableFuture.supplyAsync(() -> commandBus.send(new InnerCommand(), metadata))
            .orTimeout(5, SECONDS)
            .join()));

        var outer = spans.span("send", OuterCommand.class);
        assertThat(spans.span("send", InnerCommand.class))
            .hasParent(outer)
            .hasLinksSatisfying(linksTo(outer));
    }

    @Test
    void send_fromAnUnnamedVirtualThread_namesTheThreadByItsId() throws InterruptedException {
        var thread = Thread.ofVirtual().start(() -> commandBus.send(new InnerCommand()));

        assertThat(thread.join(Duration.ofSeconds(5))).isTrue();

        assertThat(spans.span("send", InnerCommand.class)).hasAttribute(THREAD_NAME, "virtual-" + thread.threadId());
    }

    record PlaceOrder(String customer) implements Command<String> {
    }

    record FailingCommand(RuntimeException failure) implements Command<Object> {
    }

    record ReadMetadata() implements Command<Metadata> {
    }

    record OuterCommand(Function<Metadata, Object> handling) implements Command<Object> {
    }

    record InnerCommand() implements Command<Object> {
    }

    public static class Handlers {

        @CommandHandler
        public String handle(PlaceOrder command) {
            return "accepted " + command.customer();
        }

        @CommandHandler
        public Object handle(FailingCommand command) {
            throw command.failure();
        }

        @CommandHandler
        public Metadata handle(ReadMetadata command, Metadata metadata) {
            return metadata;
        }

        @CommandHandler
        public Object handle(OuterCommand command, Metadata metadata) {
            return command.handling().apply(metadata);
        }

        @CommandHandler
        public Object handle(InnerCommand command) {
            return null;
        }
    }
}
