package app.dodb.smd.eventstore.storage;

public interface EventStorage {

    void store(SerializedEvent event);

    Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit);
}
