package app.dodb.smd.eventstore.storage;

import java.util.List;

public interface EventStorage {

    void append(NewEvent event);

    void append(NewEvent event, AggregateRevision revision);

    Cursor<StoredEvent> load(long afterGlobalPosition, int limit);

    List<StoredEvent> loadAggregateStream(String aggregateType, String aggregateId);
}
