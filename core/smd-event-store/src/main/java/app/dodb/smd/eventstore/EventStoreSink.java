package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.storage.AggregateRevision;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.serialization.EventSerializer;

import java.util.Optional;

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
        var aggregateRevision = PendingAggregateEvent.claim(eventMessage);
        transactionProvider.defer(() -> store(eventMessage, aggregateRevision));
    }

    private <E extends Event> void store(EventMessage<E> eventMessage, Optional<AggregateRevision> aggregateRevision) {
        var serializedEvent = eventSerializer.serialize(eventMessage);
        aggregateRevision.ifPresentOrElse(
            revision -> eventStorage.store(serializedEvent, revision),
            () -> eventStorage.store(serializedEvent)
        );
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }
}
