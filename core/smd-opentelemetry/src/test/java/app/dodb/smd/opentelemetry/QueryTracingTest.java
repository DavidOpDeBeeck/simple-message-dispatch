package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.command.AnnotatedCommandHandler;
import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.command.bus.CommandBus;
import app.dodb.smd.api.command.bus.CommandBusSpec;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.query.AnnotatedQueryHandler;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryHandler;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.api.query.bus.QueryBus;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static app.dodb.smd.opentelemetry.MessageSpans.DESTINATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.SpanAssertions.linksTo;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;

class QueryTracingTest {

    @RegisterExtension
    private final RecordedSpans spans = new RecordedSpans();
    private final MessageTracing tracing = new MessageTracing(spans.openTelemetry());
    private final QueryBus queryBus = QueryBusSpec.withDefaults()
        .interceptors(tracing)
        .queryHandlers(() -> AnnotatedQueryHandler.from(new QueryHandlers()))
        .create();
    private final CommandBus commandBus = CommandBusSpec.withDefaults()
        .interceptors(tracing)
        .commandHandlers(() -> AnnotatedCommandHandler.from(new CommandHandlers(queryBus)))
        .create();

    @Test
    void send_query_returnsTheResultAndRecordsAProducerSendSpan() {
        var message = QueryMessage.from(new FindOrder(), Metadata.withProperties(Map.of()));

        var result = queryBus.send(message);

        assertThat(result).isEqualTo("order");
        assertThat(spans.span("send", FindOrder.class))
            .hasKind(PRODUCER)
            .hasAttributesSatisfying(
                equalTo(OPERATION_TYPE, "send"),
                equalTo(OPERATION_NAME, "send"),
                equalTo(DESTINATION_NAME, FindOrder.class.getName()),
                equalTo(MESSAGE_KIND, "query"),
                equalTo(MESSAGE_ID, message.messageId().value().toString()));
    }

    @Test
    void send_queryFromACommandHandler_isAChildOfTheCommandsSendAndLinksToIt() {
        commandBus.send(new PlaceOrder());

        var command = spans.span("send", PlaceOrder.class);
        assertThat(spans.span("send", FindOrder.class)).hasParent(command).hasLinksSatisfying(linksTo(command));
    }

    record FindOrder() implements Query<String> {
    }

    record PlaceOrder() implements Command<String> {
    }

    public static class QueryHandlers {

        @QueryHandler
        public String handle(FindOrder query) {
            return "order";
        }
    }

    public static class CommandHandlers {

        private final QueryBus queryBus;

        public CommandHandlers(QueryBus queryBus) {
            this.queryBus = queryBus;
        }

        @CommandHandler
        public String handle(PlaceOrder command) {
            return queryBus.send(new FindOrder());
        }
    }
}
