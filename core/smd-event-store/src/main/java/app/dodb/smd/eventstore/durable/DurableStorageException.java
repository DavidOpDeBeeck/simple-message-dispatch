package app.dodb.smd.eventstore.durable;

public class DurableStorageException extends RuntimeException {

    public DurableStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
