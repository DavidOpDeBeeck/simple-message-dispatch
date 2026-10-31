package app.dodb.smd.eventstore.processing;

public class TokenStoreException extends RuntimeException {

    public TokenStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
