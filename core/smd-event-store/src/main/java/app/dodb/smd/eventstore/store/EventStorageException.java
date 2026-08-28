package app.dodb.smd.eventstore.store;

public class EventStorageException extends RuntimeException {

    public EventStorageException(String message) {
        super(message);
    }

    public EventStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
