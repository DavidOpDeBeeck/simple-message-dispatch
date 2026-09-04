package app.dodb.smd.eventstore.storage;

public class EventStorageException extends RuntimeException {

    public EventStorageException(String message) {
        super(message);
    }

    public EventStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
