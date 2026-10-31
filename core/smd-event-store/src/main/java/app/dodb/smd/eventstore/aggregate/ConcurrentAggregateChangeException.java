package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Subject;

public class ConcurrentAggregateChangeException extends RuntimeException {

    private final String aggregateType;
    private final String aggregateId;
    private final long expectedVersion;

    public ConcurrentAggregateChangeException(Subject aggregate, long expectedVersion) {
        super("Aggregate was changed concurrently: aggregateType=%s, aggregateId=%s, expectedVersion=%d"
            .formatted(aggregate.type(), aggregate.id(), expectedVersion));
        this.aggregateType = aggregate.type();
        this.aggregateId = aggregate.id();
        this.expectedVersion = expectedVersion;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
