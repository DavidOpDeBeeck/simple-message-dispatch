package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.eventstore.EventStore;

import static java.util.Objects.requireNonNull;

public class EventSourcedAggregates {

    private final EventStore eventStore;
    private final EventPublisher eventPublisher;
    private final MetadataFactory metadataFactory;

    public EventSourcedAggregates(EventStore eventStore, EventPublisher eventPublisher, MetadataFactory metadataFactory) {
        this.eventStore = requireNonNull(eventStore);
        this.eventPublisher = requireNonNull(eventPublisher);
        this.metadataFactory = requireNonNull(metadataFactory);
    }

    public <A> AggregateRepository<A> repository(Class<A> aggregateClass) {
        return new AggregateRepository<>(AggregateModel.inspect(aggregateClass), eventStore, eventPublisher, metadataFactory);
    }
}
