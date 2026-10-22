package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.serialization.EventSerializer;

import static java.util.Objects.requireNonNull;

class DurableOutbox implements EventSink {

    private final String name;
    private final OutboxStorage storage;
    private final TransactionProvider transactionProvider;
    private final EventSerializer eventSerializer;

    DurableOutbox(String name, OutboxStorage storage, DurableDeliveryConfig config) {
        this.name = requireNonNull(name);
        this.storage = requireNonNull(storage);
        this.transactionProvider = config.getTransactionProvider();
        this.eventSerializer = config.getEventSerializer();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        transactionProvider.defer(() -> storage.append(name, eventSerializer.serialize(eventMessage)));
    }

    @Override
    public EventDelivery delivery() {
        return DurableDelivery.OUTBOX;
    }
}
