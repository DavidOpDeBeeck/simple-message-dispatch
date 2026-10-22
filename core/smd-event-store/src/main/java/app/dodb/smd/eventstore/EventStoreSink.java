package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.serialization.EventSerializer;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.EventStorage;

import static java.util.Objects.requireNonNull;

class EventStoreSink implements EventSink {

    private final EventLog log;
    private final TransactionProvider transactionProvider;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;

    EventStoreSink(EventStoreConfig config, EventLog log) {
        requireNonNull(config);
        this.log = requireNonNull(log);
        this.transactionProvider = config.getTransactionProvider();
        this.eventStorage = config.getEventStorage();
        this.eventSerializer = config.getEventSerializer();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        transactionProvider.defer(() -> eventStorage.store(log, eventSerializer.serialize(eventMessage)));
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }
}
