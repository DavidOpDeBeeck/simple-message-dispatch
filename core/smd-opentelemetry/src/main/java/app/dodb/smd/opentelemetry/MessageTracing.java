package app.dodb.smd.opentelemetry;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusInterceptorChain;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroupContext;
import app.dodb.smd.api.event.ProcessingGroupInterceptor;
import app.dodb.smd.api.event.ProcessingGroupInterceptorChain;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.EventBusInterceptorChain;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.api.query.bus.QueryBusInterceptorChain;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;

import static app.dodb.smd.opentelemetry.MessageSpans.COMMAND;
import static app.dodb.smd.opentelemetry.MessageSpans.DELIVERY;
import static app.dodb.smd.opentelemetry.MessageSpans.EVENT;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESS;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESSING_GROUP;
import static app.dodb.smd.opentelemetry.MessageSpans.PUBLISH;
import static app.dodb.smd.opentelemetry.MessageSpans.QUERY;
import static app.dodb.smd.opentelemetry.MessageSpans.SEND;
import static io.opentelemetry.api.trace.SpanKind.CONSUMER;
import static io.opentelemetry.api.trace.SpanKind.PRODUCER;

public class MessageTracing implements CommandBusInterceptor, QueryBusInterceptor, EventBusInterceptor, EventSinkInterceptor, ProcessingGroupInterceptor {

    private static final MessagingOperation SENDING = new MessagingOperation(SEND, SEND, PRODUCER);
    private static final MessagingOperation PUBLISHING = new MessagingOperation(PUBLISH, SEND, PRODUCER);
    private static final MessagingOperation PROCESSING = new MessagingOperation(PROCESS, PROCESS, CONSUMER);
    private static final Attributes COMMANDS = Attributes.of(MESSAGE_KIND, COMMAND);
    private static final Attributes QUERIES = Attributes.of(MESSAGE_KIND, QUERY);
    private static final Attributes EVENTS = Attributes.of(MESSAGE_KIND, EVENT);

    private final MessageTracer tracer;

    public MessageTracing(OpenTelemetry openTelemetry) {
        this.tracer = new MessageTracer(openTelemetry);
    }

    @Override
    public <R, C extends Command<R>> R intercept(CommandMessage<R, C> commandMessage, CommandBusInterceptorChain<R, C> chain) {
        return tracer.trace(SENDING, COMMANDS, commandMessage, chain::proceed);
    }

    @Override
    public <R, Q extends Query<R>> R intercept(QueryMessage<R, Q> queryMessage, QueryBusInterceptorChain<R, Q> chain) {
        return tracer.trace(SENDING, QUERIES, queryMessage, chain::proceed);
    }

    @Override
    public <E extends Event> void intercept(EventMessage<E> eventMessage, EventBusInterceptorChain<E> chain) {
        tracer.trace(PUBLISHING, EVENTS, eventMessage, traced -> {
            chain.proceed(traced);
            return null;
        });
    }

    @Override
    public <E extends Event> void intercept(EventSinkContext<E> context, EventSinkInterceptorChain<E> chain) {
        var attributes = EVENTS.toBuilder()
            .put(DELIVERY, context.sink().delivery().name())
            .build();

        tracer.trace(SENDING, attributes, context.eventMessage(), _ -> {
            chain.proceed(context);
            return null;
        });
    }

    @Override
    public <E extends Event> void intercept(ProcessingGroupContext<E> context, ProcessingGroupInterceptorChain<E> chain) {
        var attributes = EVENTS.toBuilder()
            .put(PROCESSING_GROUP, context.processingGroup())
            .put(DELIVERY, context.source().delivery().name())
            .build();

        tracer.trace(PROCESSING, attributes, context.eventMessage(), traced -> {
            chain.proceed(context.withEventMessage(traced));
            return null;
        });
    }
}
