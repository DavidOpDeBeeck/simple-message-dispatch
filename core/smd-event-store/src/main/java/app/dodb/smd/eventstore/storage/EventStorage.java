package app.dodb.smd.eventstore.storage;

public interface EventStorage {

    void store(EventLog log, SerializedEvent event);

    boolean storeIfAbsent(EventLog log, SerializedEvent event);

    Cursor<SerializedEvent> load(EventLog log, long lastProcessedSequenceNumber, int limit);

    long countStoredInOtherLogs(EventLog log, long afterSequenceNumber, long beforeSequenceNumber);
}
