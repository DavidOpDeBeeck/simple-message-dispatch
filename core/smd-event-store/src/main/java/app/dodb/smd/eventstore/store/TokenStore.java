package app.dodb.smd.eventstore.store;

public interface TokenStore {

    Token getToken(String processingGroup);
}
