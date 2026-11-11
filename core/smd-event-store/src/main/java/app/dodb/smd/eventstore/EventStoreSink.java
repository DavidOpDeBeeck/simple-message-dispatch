package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.storage.EventStorage;
import app.dodb.smd.eventstore.storage.NewEvent;
import app.dodb.smd.serialization.EventSerializer;
import app.dodb.smd.serialization.SerializedEvent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
        var serializedEvent = eventSerializer.serialize(eventMessage);
        var newEvent = subjectVersion
            .map(version -> NewEvent.from(serializedEvent, version))
            .orElseGet(() -> NewEvent.from(serializedEvent));

        if (subjectVersion.isEmpty()) {
            transactionProvider.defer(() -> eventStorage.append(newEvent));
            return;
        }

        var pendingSubjectStream = newEvent.subject()
            .map(this::pendingSubjectStream)
            .orElseThrow();
        pendingSubjectStream.add(serializedEvent);
        transactionProvider.defer(() -> {
            eventStorage.append(newEvent);
            pendingSubjectStream.remove(serializedEvent);
        });
    }

    List<SerializedEvent> pendingSubjectStream(Subject subject) {
        return transactionProvider.resource(this, () -> new ConcurrentHashMap<Subject, List<SerializedEvent>>())
            .computeIfAbsent(subject, _ -> new CopyOnWriteArrayList<>());
    }

    @Override
    public EventDelivery delivery() {
        return EventStore.DELIVERY;
    }
}
