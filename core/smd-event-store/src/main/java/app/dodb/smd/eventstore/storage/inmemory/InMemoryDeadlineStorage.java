package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.eventstore.deadline.ClaimedDeadline;
import app.dodb.smd.eventstore.deadline.DeadlineState;
import app.dodb.smd.eventstore.deadline.DeadlineStatus;
import app.dodb.smd.eventstore.deadline.DeadlineStorage;
import app.dodb.smd.eventstore.deadline.SerializedDeadline;
import app.dodb.smd.eventstore.storage.DeadlineStorageException;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static app.dodb.smd.eventstore.deadline.DeadlineStatus.ABANDONED;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.FAILED;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.PENDING;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

// Not transactional: a claim is not released by a rollback, and changes are visible immediately.
public class InMemoryDeadlineStorage implements DeadlineStorage {

    private final Map<DeadlineId, StoredDeadline> deadlines = new LinkedHashMap<>();

    @Override
    public synchronized void store(SerializedDeadline deadline) {
        if (deadlines.containsKey(deadline.deadlineId())) {
            throw new DeadlineStorageException("Deadline already stored: deadlineId=" + deadline.deadlineId().value());
        }
        deadlines.put(deadline.deadlineId(), new StoredDeadline(deadline));
    }

    @Override
    public synchronized boolean delete(DeadlineId deadlineId) {
        var deadline = deadlines.get(deadlineId);
        if (deadline == null || deadline.status == ABANDONED) {
            return false;
        }
        deadlines.remove(deadlineId);
        return true;
    }

    @Override
    public synchronized Optional<ClaimedDeadline> claimDue(Instant now) {
        return deadlines.values().stream()
            .filter(deadline -> deadline.status != ABANDONED && !deadline.nextAttemptAt.isAfter(now))
            .min(Comparator.comparing(deadline -> deadline.nextAttemptAt))
            .map(InMemoryClaimedDeadline::new);
    }

    @Override
    public synchronized Optional<ClaimedDeadline> claim(DeadlineId deadlineId) {
        return Optional.ofNullable(deadlines.get(deadlineId))
            .filter(deadline -> deadline.status != ABANDONED)
            .map(InMemoryClaimedDeadline::new);
    }

    @Override
    public synchronized Optional<DeadlineState> deadlineState(DeadlineId deadlineId) {
        return Optional.ofNullable(deadlines.get(deadlineId)).map(StoredDeadline::state);
    }

    private static class StoredDeadline {

        private final SerializedDeadline deadline;
        private Instant nextAttemptAt;
        private DeadlineStatus status = PENDING;
        private int errorCount;
        private String lastErrorMessage;
        private Instant lastErrorAt;

        private StoredDeadline(SerializedDeadline deadline) {
            this.deadline = requireNonNull(deadline);
            this.nextAttemptAt = deadline.dueAt();
        }

        private DeadlineState state() {
            return new DeadlineState(
                deadline.deadlineId(),
                deadline.commandType(),
                deadline.dueAt(),
                nextAttemptAt,
                status,
                errorCount,
                Optional.ofNullable(lastErrorMessage),
                Optional.ofNullable(lastErrorAt)
            );
        }
    }

    private class InMemoryClaimedDeadline implements ClaimedDeadline {

        private final StoredDeadline stored;
        private final int errorCount;

        private InMemoryClaimedDeadline(StoredDeadline stored) {
            this.stored = requireNonNull(stored);
            this.errorCount = stored.errorCount;
        }

        @Override
        public SerializedDeadline deadline() {
            return stored.deadline;
        }

        @Override
        public int errorCount() {
            return errorCount;
        }

        @Override
        public void markFired() {
            synchronized (InMemoryDeadlineStorage.this) {
                deadlines.remove(stored.deadline.deadlineId());
            }
        }

        @Override
        public void markFailed(Exception exception, int errorCount, Instant failedAt, Instant nextAttemptAt) {
            synchronized (InMemoryDeadlineStorage.this) {
                stored.status = FAILED;
                stored.errorCount = errorCount;
                stored.nextAttemptAt = nextAttemptAt;
                stored.lastErrorMessage = getStackTraceAsString(exception);
                stored.lastErrorAt = failedAt;
            }
        }

        @Override
        public void markAbandoned(Exception exception, Instant abandonedAt) {
            synchronized (InMemoryDeadlineStorage.this) {
                stored.status = ABANDONED;
                stored.lastErrorMessage = getStackTraceAsString(exception);
                stored.lastErrorAt = abandonedAt;
            }
        }
    }
}
