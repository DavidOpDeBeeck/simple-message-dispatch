package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.command.AnnotatedCommandHandler;
import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusInterceptorChain;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.EventBus;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.metadata.Metadata;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.dodb.smd.opentelemetry.MessageSpans.DELIVERY;
import static app.dodb.smd.opentelemetry.MessageSpans.ERROR_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESSING_GROUP;
import static app.dodb.smd.opentelemetry.SpanAssertions.linksTo;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingGroupTracingTest {

    @RegisterExtension
    private final RecordedSpans spans = new RecordedSpans();
    private final MessageTracing tracing = new MessageTracing(spans.openTelemetry());
    private final ReceivedCommands receivedCommands = new ReceivedCommands();
    private final CommandBus commandBus = CommandBusSpec.withDefaults()
        .interceptors(tracing, receivedCommands)
        .commandHandlers(() -> AnnotatedCommandHandler.from(new CommandHandlers()))
        .create();
    private final EventBus eventBus = eventBus(ProcessingGroupsConfigurer.defaultSynchronous());

    @Test
    void publish_syncGroup_recordsAConsumerProcessSpanWithinTheSyncSend() {
        var message = EventMessage.from(new OrderPlaced(), Metadata.now());

        eventBus.publish(message);

        assertThat(spans.span("process", OrderPlaced.class))
            .hasKind(CONSUMER)
            .hasParent(spans.span("send", OrderPlaced.class))
            .hasLinksSatisfying(linksTo(spans.span("publish", OrderPlaced.class)))
            .hasAttributesSatisfying(
                equalTo(OPERATION_TYPE, "process"),
                equalTo(OPERATION_NAME, "process"),
                equalTo(MESSAGE_KIND, "event"),
                equalTo(PROCESSING_GROUP, "projections"),
                equalTo(DELIVERY, "sync"),
                equalTo(MESSAGE_ID, message.messageId().value().toString()));
    }

    @Test
    void publish_asyncGroup_continuesThePublicationOnTheWorkerThread() {
        var asyncEventBus = eventBus(groups -> groups.anyProcessingGroup().async().await());

        asyncEventBus.publish(new OrderPlaced());

        var publication = spans.span("publish", OrderPlaced.class);
        assertThat(spans.span("process", OrderPlaced.class))
            .hasParent(publication)
            .hasLinksSatisfying(linksTo(publication))
            .hasAttribute(DELIVERY, "async-await");
    }

    @Test
    void publish_failingHandler_rethrowsTheFailureAndMarksTheProcessSpanFailed() {
        var failure = new IllegalStateException("projection is behind");

        assertThatThrownBy(() -> eventBus.publish(new ProjectionFailed(failure))).isSameAs(failure);

        assertThat(spans.span("process", ProjectionFailed.class))
            .hasStatus(StatusData.error())
            .hasAttribute(ERROR_TYPE, IllegalStateException.class.getName());
    }

    @Test
    void publish_handlerSendingACommand_sendsItAsAChildOfTheProcessSpan() {
        eventBus.publish(new OrderShipped());

        var handling = spans.span("process", OrderShipped.class);
        assertThat(spans.span("send", UpdateProjection.class))
            .hasParent(handling)
            .hasLinksSatisfying(linksTo(handling));
    }

    @Test
    void publish_eventCarryingBaggageWithinAnApplicationSpan_passesTheBaggageToTheCommandItsHandlerSends() {
        var message = EventMessage.from(new OrderShipped(), Metadata.now().withProperty("baggage", "tenant=acme"));
        var request = spans.startApplicationSpan("http-request");

        try (var _ = request.makeCurrent()) {
            eventBus.publish(message);
        } finally {
            request.end();
        }

        assertThat(receivedCommands.metadata).singleElement()
            .satisfies(metadata -> assertThat(baggageEntries(metadata)).containsExactly("tenant=acme"));
    }

    @Test
    void publish_eventBaggageConflictingWithTheCurrentBaggage_keepsBothWithTheEventsEntriesWinning() {
        var message = EventMessage.from(new OrderShipped(), Metadata.now().withProperty("baggage", "tenant=acme"));
        var request = spans.startApplicationSpan("http-request");
        var currentBaggage = Baggage.builder().put("tenant", "other").put("region", "eu").build();

        try (var _ = Context.current().with(request).with(currentBaggage).makeCurrent()) {
            eventBus.publish(message);
        } finally {
            request.end();
        }

        assertThat(receivedCommands.metadata).singleElement()
            .satisfies(metadata -> assertThat(baggageEntries(metadata)).containsExactlyInAnyOrder("tenant=acme", "region=eu"));
    }

    private static List<String> baggageEntries(Metadata metadata) {
        return List.of(metadata.properties().get("baggage").split(","));
    }

    private EventBus eventBus(ProcessingGroupsConfigurer processingGroups) {
        return EventBusSpec.withDefaults()
            .processingGroups(() -> AnnotatedEventHandler.from(new Projections(commandBus)), processingGroups)
            .interceptors(tracing)
            .sinkInterceptors(tracing)
            .processingGroupInterceptors(tracing)
            .create();
    }

    record OrderPlaced() implements Event {
    }

    record OrderShipped() implements Event {
    }

    record ProjectionFailed(RuntimeException failure) implements Event {
    }

    record UpdateProjection() implements Command<Object> {
    }

    @ProcessingGroup("projections")
    public static class Projections {

        private final CommandBus commandBus;

        public Projections(CommandBus commandBus) {
            this.commandBus = commandBus;
        }

        @EventHandler
        public void on(OrderPlaced event) {
        }

        @EventHandler
        public void on(OrderShipped event) {
            commandBus.send(new UpdateProjection());
        }

        @EventHandler
        public void on(ProjectionFailed event) {
            throw event.failure();
        }
    }

    static class ReceivedCommands implements CommandBusInterceptor {

        private final List<Metadata> metadata = new CopyOnWriteArrayList<>();

        @Override
        public <R, C extends Command<R>> R intercept(CommandMessage<R, C> commandMessage, CommandBusInterceptorChain<R, C> chain) {
            metadata.add(commandMessage.metadata());
            return chain.proceed(commandMessage);
        }
    }

    public static class CommandHandlers {

        @CommandHandler
        public Object handle(UpdateProjection command) {
            return null;
        }
    }
}
