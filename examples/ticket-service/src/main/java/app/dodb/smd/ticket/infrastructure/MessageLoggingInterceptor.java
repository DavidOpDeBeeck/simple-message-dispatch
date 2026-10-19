package app.dodb.smd.ticket.infrastructure;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBusInterceptor;
import app.dodb.smd.api.command.bus.CommandBusInterceptorChain;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.bus.EventBusInterceptor;
import app.dodb.smd.api.event.bus.EventBusInterceptorChain;
import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.api.query.bus.QueryBusInterceptor;
import app.dodb.smd.api.query.bus.QueryBusInterceptorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class MessageLoggingInterceptor implements CommandBusInterceptor, QueryBusInterceptor, EventBusInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageLoggingInterceptor.class);

    @Override
    public <R, C extends Command<R>> R intercept(CommandMessage<R, C> commandMessage, CommandBusInterceptorChain<R, C> chain) {
        long started = System.nanoTime();
        try {
            return chain.proceed(commandMessage);
        } finally {
            log("command", commandMessage, started);
        }
    }

    @Override
    public <R, Q extends Query<R>> R intercept(QueryMessage<R, Q> queryMessage, QueryBusInterceptorChain<R, Q> chain) {
        long started = System.nanoTime();
        try {
            return chain.proceed(queryMessage);
        } finally {
            log("query", queryMessage, started);
        }
    }

    @Override
    public <E extends Event> void intercept(EventMessage<E> eventMessage, EventBusInterceptorChain<E> chain) {
        long started = System.nanoTime();
        try {
            chain.proceed(eventMessage);
        } finally {
            log("event", eventMessage, started);
        }
    }

    private static void log(String kind, Message<?, ?> message, long started) {
        var metadata = message.metadata();
        var parentId = metadata.parentMessageId() == null ? null : metadata.parentMessageId().value();
        LOGGER.info("Dispatched {}: type={}, messageId={}, parentId={}, customerId={}, durationMs={}",
            kind,
            message.payload().getClass().getSimpleName(),
            message.messageId().value(),
            parentId,
            metadata.properties().get("customerId"),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
