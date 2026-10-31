package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.serialization.EventSerializer;

import java.util.Optional;

import static app.dodb.smd.eventstore.EventStoreMetadata.subjectVersion;
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
        var subjectVersion = subjectVersion(eventMessage.metadata());
        transactionProvider.defer(() -> store(eventMessage, subjectVersion));
    }

    private <E extends Event> void store(EventMessage<E> eventMessage, Optional<Long> subjectVersion) {
        var serializedEvent = eventSerializer.serialize(eventMessage);
        var newEvent = subjectVersion
            .map(version -> NewEvent.from(serializedEvent, version))
            .orElseGet(() -> NewEvent.from(serializedEvent));
        eventStorage.append(newEvent);
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }
}
