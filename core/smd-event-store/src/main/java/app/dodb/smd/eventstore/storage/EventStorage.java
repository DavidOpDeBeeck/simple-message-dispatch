package app.dodb.smd.eventstore.storage;

import app.dodb.smd.serialization.SerializedEvent;

public interface EventStorage {

    void store(SerializedEvent event);

    Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit);
}
