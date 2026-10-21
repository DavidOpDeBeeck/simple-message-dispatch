package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSinkContext;
import app.dodb.smd.api.event.delivery.EventSinkInterceptor;
import app.dodb.smd.api.event.delivery.EventSinkInterceptorChain;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.EventStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static app.dodb.smd.eventstore.EventLogConsumption.HANDLING;
import static java.util.Objects.requireNonNull;

class EventStoreInbox implements EventSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStoreInbox.class);

    private final EventStoreConfig config;
    private final EventLogPolling polling;
    private final EventSource source;

    EventStoreInbox(EventStoreConfig config, EventLogPolling polling, EventSource source) {
        this.config = requireNonNull(config);
        this.polling = requireNonNull(polling);
        this.source = requireNonNull(source);
    }

    @Override
    public EventSubscription subscribe(EventSubscriber subscriber) {
        var log = EventLog.inbox(subscriber.processingGroup());
        var receiving = source.subscribe(new InboxReceiver(subscriber.processingGroup(), new InboxSink(log), config.getSinkInterceptors()));
        var delivering = polling.schedule(new EventStoreTokenProcessor(config, log, HANDLING), subscriber);
        return () -> {
            receiving.close();
            delivering.close();
        };
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }

    private static class InboxReceiver implements EventSubscriber {

        private final String processingGroup;
        private final EventSink inboxSink;
        private final List<EventSinkInterceptor> sinkInterceptors;

        private InboxReceiver(String processingGroup, EventSink inboxSink, List<EventSinkInterceptor> sinkInterceptors) {
            this.processingGroup = requireNonNull(processingGroup);
            this.inboxSink = requireNonNull(inboxSink);
            this.sinkInterceptors = requireNonNull(sinkInterceptors);
        }

        @Override
        public String processingGroup() {
            return processingGroup;
        }

        @Override
        public <E extends Event> void on(EventMessage<E> eventMessage) {
            EventSinkInterceptorChain.<E>create(context -> context.sink().send(context.eventMessage()), sinkInterceptors)
                .proceed(new EventSinkContext<>(inboxSink, eventMessage));
        }
    }

    private class InboxSink implements EventSink {

        private final EventLog log;
        private final TransactionProvider transactionProvider;
        private final EventStorage eventStorage;
        private final EventSerializer eventSerializer;

        private InboxSink(EventLog log) {
            this.log = requireNonNull(log);
            this.transactionProvider = config.getTransactionProvider();
            this.eventStorage = config.getEventStorage();
            this.eventSerializer = config.getEventSerializer();
        }

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            transactionProvider.defer(() -> {
                if (!eventStorage.storeIfAbsent(log, eventSerializer.serialize(eventMessage))) {
                    LOGGER.debug("Duplicate inbox event ignored: log={}, messageId={}", log.name(), eventMessage.messageId());
                }
            });
        }

        @Override
        public EventDelivery delivery() {
            return EventStore.DELIVERY;
        }
    }
}
