package app.dodb.smd.eventstore.storage;

import java.time.Duration;
import java.util.Optional;

public interface TokenStore {

    Optional<Token> claimToken(String processingGroup);

    Optional<Token> claimTokenForReset(String processingGroup, Duration lockTimeout);

    Optional<TokenState> tokenState(String processingGroup);
}
