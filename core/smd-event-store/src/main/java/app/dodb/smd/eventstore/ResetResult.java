package app.dodb.smd.eventstore;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public sealed interface ResetResult {

    String processingGroup();

    record Reset(String processingGroup,
                 Optional<Long> previousPosition,
                 Optional<Long> replayUntil) implements ResetResult {

        public Reset {
            requireNonNull(processingGroup);
            requireNonNull(previousPosition);
            requireNonNull(replayUntil);
        }
    }

    record TokenClaimed(String processingGroup) implements ResetResult {

        public TokenClaimed {
            requireNonNull(processingGroup);
        }
    }

    record UnknownProcessingGroup(String processingGroup) implements ResetResult {

        public UnknownProcessingGroup {
            requireNonNull(processingGroup);
        }
    }
}
