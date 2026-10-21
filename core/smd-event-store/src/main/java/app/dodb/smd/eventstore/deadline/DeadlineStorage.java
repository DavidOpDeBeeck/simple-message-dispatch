package app.dodb.smd.eventstore.deadline;

import app.dodb.smd.api.deadline.DeadlineId;

import java.time.Instant;
import java.util.Optional;

public interface DeadlineStorage {

    void store(SerializedDeadline deadline);

    boolean delete(DeadlineId deadlineId);

    // A claim lasts until the surrounding transaction ends; claimed deadlines are skipped by other claims.
    Optional<ClaimedDeadline> claimDue(Instant now);

    Optional<ClaimedDeadline> claim(DeadlineId deadlineId);

    Optional<DeadlineState> deadlineState(DeadlineId deadlineId);
}
