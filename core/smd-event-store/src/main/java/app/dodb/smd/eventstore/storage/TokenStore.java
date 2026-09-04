package app.dodb.smd.eventstore.storage;

import java.util.Optional;

public interface TokenStore {

    Optional<Token> claimToken(String processingGroup);

    Optional<TokenState> tokenState(String processingGroup);
}
