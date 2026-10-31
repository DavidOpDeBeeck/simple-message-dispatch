package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.event.Subject;

import java.util.List;

public interface EventStorage {

    void append(NewEvent event);

    Cursor<StoredEvent> load(long afterGlobalPosition, int limit);

    List<StoredEvent> loadSubjectStream(Subject subject);
}
