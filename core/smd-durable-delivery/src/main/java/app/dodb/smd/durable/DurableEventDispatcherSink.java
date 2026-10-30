package app.dodb.smd.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.durable.storage.DispatcherStorage;
import app.dodb.smd.serialization.EventSerializer;

import static java.util.Objects.requireNonNull;

class DurableEventDispatcherSink implements EventSink {

    private final String dispatcher;
    private final TransactionProvider transactionProvider;
    private final DispatcherStorage dispatcherStorage;
    private final EventSerializer eventSerializer;

    DurableEventDispatcherSink(String dispatcher, DurableEventDispatcherConfig config) {
        requireNonNull(config);
        this.dispatcher = requireNonNull(dispatcher);
        this.transactionProvider = config.getTransactionProvider();
        this.dispatcherStorage = config.getDispatcherStorage();
        this.eventSerializer = config.getEventSerializer();
    }

    @Override
    public <E extends Event> void send(EventMessage<E> eventMessage) {
        transactionProvider.defer(() -> dispatcherStorage.store(dispatcher, eventSerializer.serialize(eventMessage)));
    }

    @Override
    public EventDelivery delivery() {
        return DurableEventDispatcher.DELIVERY;
    }
}
