package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.serialization.EventSerializer;

import static java.util.Objects.requireNonNull;

class EventStoreSink implements EventSink {

    private final TransactionProvider transactionProvider;
    private final EventStorage eventStorage;
    private final EventSerializer eventSerializer;

    EventStoreSink(EventStoreConfig config) {
        requireNonNull(config);
        this.transactionProvider = config.getTransactionProvider();
        this.eventStorage = config.getEventStorage();
        this.eventSerializer = config.getEventSerializer();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        transactionProvider.defer(() -> eventStorage.store(eventSerializer.serialize(eventMessage)));
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }
}
