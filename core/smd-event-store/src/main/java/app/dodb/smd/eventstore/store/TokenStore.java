package app.dodb.smd.eventstore.store;

import java.util.Optional;

public interface TokenStore {

    Optional<Token> claimToken(String processingGroup);
}
