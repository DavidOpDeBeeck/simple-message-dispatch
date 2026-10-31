package app.dodb.smd.eventstore.aggregate;

import static java.util.Objects.requireNonNull;

public class AggregateNotFoundException extends RuntimeException {

    private final String aggregateType;
    private final String aggregateId;

    public AggregateNotFoundException(String aggregateType, String aggregateId) {
        super("Aggregate not found: aggregateType=%s, aggregateId=%s".formatted(aggregateType, aggregateId));
        this.aggregateType = requireNonNull(aggregateType);
        this.aggregateId = requireNonNull(aggregateId);
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }
}
