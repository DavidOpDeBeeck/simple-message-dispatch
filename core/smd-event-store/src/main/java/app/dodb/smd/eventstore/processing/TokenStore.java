package app.dodb.smd.eventstore.processing;

import java.util.Optional;

public interface TokenStore {

    Optional<app.dodb.smd.eventstore.processing.Token> claimToken(String processingGroup);

    Optional<TokenState> tokenState(String processingGroup);
}
