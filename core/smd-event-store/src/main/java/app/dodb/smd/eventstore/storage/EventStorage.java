package app.dodb.smd.eventstore.storage;

import app.dodb.smd.serialization.SerializedEvent;

import java.util.List;

public interface EventStorage {

    void store(SerializedEvent event);

    void store(SerializedEvent event, AggregateRevision revision);

    Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit);

    List<SerializedEvent> loadAggregateStream(String aggregateType, String aggregateId);
}
