package app.dodb.smd.eventstore.store.serialization;

public class EventTypeResolutionException extends Exception {

    public EventTypeResolutionException(String message) {
        super(message);
    }

    public EventTypeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
