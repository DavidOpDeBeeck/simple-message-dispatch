package app.dodb.smd.eventstore.storage;

public class TokenStoreException extends RuntimeException {

    public TokenStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
