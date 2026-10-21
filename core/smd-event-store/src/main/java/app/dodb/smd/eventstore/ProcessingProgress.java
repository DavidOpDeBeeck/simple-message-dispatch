package app.dodb.smd.eventstore;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record ProcessingProgress(String processingGroup,
                                 long position,
                                 long head,
                                 Optional<Long> replayUntil) {

    public ProcessingProgress {
        requireNonNull(processingGroup);
        requireNonNull(replayUntil);
    }

    public long remaining() {
        return Math.max(0L, head - position);
    }

    public boolean replaying() {
        return replayUntil.filter(until -> position < until).isPresent();
    }
}
