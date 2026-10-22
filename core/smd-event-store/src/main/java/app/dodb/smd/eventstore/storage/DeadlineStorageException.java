package app.dodb.smd.eventstore.storage;

public class DeadlineStorageException extends RuntimeException {

    public DeadlineStorageException(String message) {
        super(message);
    }

    public DeadlineStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
