package app.dodb.smd.eventstore.storage;

public class TokenStoreException extends RuntimeException {

    public TokenStoreException(String message) {
        super(message);
    }

    public TokenStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
