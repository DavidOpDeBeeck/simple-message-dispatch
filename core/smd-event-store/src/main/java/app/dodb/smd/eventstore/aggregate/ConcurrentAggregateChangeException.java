package app.dodb.smd.eventstore.aggregate;

import static java.util.Objects.requireNonNull;

public class ConcurrentAggregateChangeException extends RuntimeException {

    private final String aggregateType;
    private final String aggregateId;
    private final long expectedVersion;

    public ConcurrentAggregateChangeException(String aggregateType, String aggregateId, long expectedVersion) {
        super("Aggregate was changed concurrently: aggregateType=%s, aggregateId=%s, expectedVersion=%d"
            .formatted(aggregateType, aggregateId, expectedVersion));
        this.aggregateType = requireNonNull(aggregateType);
        this.aggregateId = requireNonNull(aggregateId);
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
