package app.dodb.smd.eventstore.serialization;

public class CommandTypeResolutionException extends Exception {

    public CommandTypeResolutionException(String message) {
        super(message);
    }

    public CommandTypeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
