package app.dodb.smd.eventstore.storage;

import java.util.Optional;

public interface TokenStore {

    Optional<Token> claimToken(EventLog log, String processingGroup);

    Optional<TokenState> tokenState(EventLog log, String processingGroup);

    default Optional<TokenState> tokenState(String processingGroup) {
        return tokenState(EventLog.EVENTS, processingGroup);
    }
}
