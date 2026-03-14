package app.dodb.smd.eventstore.store;

public interface EventStorage {

    void store(SerializedEvent event);

    Cursor<SerializedEvent> load(long lastProcessedSequenceNumber, int limit);
}
