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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static app.dodb.smd.eventstore.deadline.DeadlineStatus.ABANDONED;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.FAILED;
import static app.dodb.smd.eventstore.deadline.DeadlineStatus.PENDING;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

// Not transactional: a claim is not released by a rollback, and changes are visible immediately.
public class InMemoryDeadlineStorage implements DeadlineStorage {

    private final Map<DeadlineId, StoredDeadline> deadlines = new ConcurrentHashMap<>();

    @Override
    public void store(SerializedDeadline deadline) {
        if (deadlines.putIfAbsent(deadline.deadlineId(), new StoredDeadline(deadline)) != null) {
            throw new DeadlineStorageException("Deadline already stored: deadlineId=" + deadline.deadlineId().value());
        }
    }

    @Override
    public boolean delete(DeadlineId deadlineId) {
        var deleted = new AtomicBoolean();
        deadlines.computeIfPresent(deadlineId, (id, deadline) -> {
            if (deadline.status() == ABANDONED) {
                return deadline;
            }
            deleted.set(true);
            return null;
        });
        return deleted.get();
    }

    @Override
    public Optional<ClaimedDeadline> claimDue(Instant now) {
        return deadlines.values().stream()
            .filter(deadline -> deadline.status() != ABANDONED && !deadline.nextAttemptAt().isAfter(now))
            .min(Comparator.comparing(StoredDeadline::nextAttemptAt))
            .map(InMemoryClaimedDeadline::new);
    }

    @Override
    public Optional<ClaimedDeadline> claim(DeadlineId deadlineId) {
        return Optional.ofNullable(deadlines.get(deadlineId))
            .filter(deadline -> deadline.status() != ABANDONED)
            .map(InMemoryClaimedDeadline::new);
    }

    @Override
    public Optional<DeadlineState> deadlineState(DeadlineId deadlineId) {
        return Optional.ofNullable(deadlines.get(deadlineId)).map(StoredDeadline::state);
    }

    private record StoredDeadline(SerializedDeadline deadline,
                                  Instant nextAttemptAt,
                                  DeadlineStatus status,
                                  int errorCount,
                                  String lastErrorMessage,
                                  Instant lastErrorAt) {

        private StoredDeadline {
            requireNonNull(deadline);
            requireNonNull(nextAttemptAt);
            requireNonNull(status);
        }

        private StoredDeadline(SerializedDeadline deadline) {
            this(deadline, deadline.dueAt(), PENDING, 0, null, null);
        }

        private StoredDeadline failed(Exception exception, int errorCount, Instant failedAt, Instant nextAttemptAt) {
            return new StoredDeadline(deadline, nextAttemptAt, FAILED, errorCount, getStackTraceAsString(exception), failedAt);
        }

        private StoredDeadline abandoned(Exception exception, Instant abandonedAt) {
            return new StoredDeadline(deadline, nextAttemptAt, ABANDONED, errorCount, getStackTraceAsString(exception), abandonedAt);
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

        private final SerializedDeadline deadline;
        private final int errorCount;

        private InMemoryClaimedDeadline(StoredDeadline stored) {
            this.deadline = stored.deadline();
            this.errorCount = stored.errorCount();
        }

        @Override
        public SerializedDeadline deadline() {
            return deadline;
        }

        @Override
        public int errorCount() {
            return errorCount;
        }

        @Override
        public void markFired() {
            deadlines.remove(deadline.deadlineId());
        }

        @Override
        public void markFailed(Exception exception, int errorCount, Instant failedAt, Instant nextAttemptAt) {
            deadlines.computeIfPresent(deadline.deadlineId(), (id, stored) -> stored.failed(exception, errorCount, failedAt, nextAttemptAt));
        }

        @Override
        public void markAbandoned(Exception exception, Instant abandonedAt) {
            deadlines.computeIfPresent(deadline.deadlineId(), (id, stored) -> stored.abandoned(exception, abandonedAt));
        }
    }
}
