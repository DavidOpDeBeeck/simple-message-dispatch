package app.dodb.smd.eventstore.storage;

import static java.util.Objects.requireNonNull;

public record AggregateRevision(String aggregateType, String aggregateId, long version) {

    public AggregateRevision {
        requireNonNull(aggregateType);
        requireNonNull(aggregateId);
        if (version < 1L) {
            throw new IllegalArgumentException("Aggregate version must be greater than zero: " + version);
        }
    }

    public long expectedVersion() {
        return version - 1L;
    }
}
