package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.deadline.DeadlineId;
import app.dodb.smd.eventstore.deadline.ClaimedDeadline;
import app.dodb.smd.eventstore.deadline.DeadlineState;
import app.dodb.smd.eventstore.deadline.DeadlineStatus;
import app.dodb.smd.eventstore.deadline.DeadlineStorage;
import app.dodb.smd.eventstore.deadline.SerializedDeadline;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.DeadlineStorageException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalTimestamp;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

public class PostgresDeadlineStorage implements DeadlineStorage {

    private final ConnectionProvider connectionProvider;

    public PostgresDeadlineStorage(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public void store(SerializedDeadline deadline) {
        connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                INSERT INTO smd_deadline_store
                    (deadline_id, command_type, serialized_payload, serialized_metadata, due_at, next_attempt_at, status, error_count)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0)
                """)) {
                stmt.setObject(1, deadline.deadlineId().value());
                stmt.setString(2, deadline.commandType());
                stmt.setBytes(3, deadline.serializedPayload());
                stmt.setBytes(4, deadline.serializedMetadata());
                stmt.setTimestamp(5, Timestamp.from(deadline.dueAt()));
                stmt.setTimestamp(6, Timestamp.from(deadline.dueAt()));

                if (stmt.executeUpdate() != 1) {
                    throw new DeadlineStorageException("Failed to insert deadline: deadlineId=" + deadline.deadlineId().value());
                }
            } catch (SQLException e) {
                throw new DeadlineStorageException("Failed to store deadline: deadlineId=" + deadline.deadlineId().value()
                    + ", commandType=" + deadline.commandType(), e);
            }
        });
    }

    @Override
    public boolean delete(DeadlineId deadlineId) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                DELETE FROM smd_deadline_store
                WHERE deadline_id = ? AND status <> 'ABANDONED'
                """)) {
                stmt.setObject(1, deadlineId.value());
                return stmt.executeUpdate() == 1;
            } catch (SQLException e) {
                throw new DeadlineStorageException("Failed to delete deadline: deadlineId=" + deadlineId.value(), e);
            }
        });
    }

    @Override
    public Optional<ClaimedDeadline> claimDue(Instant now) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT deadline_id, command_type, serialized_payload, serialized_metadata, due_at, error_count
                FROM smd_deadline_store
                WHERE status <> 'ABANDONED' AND next_attempt_at <= ?
                ORDER BY next_attempt_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)) {
                stmt.setTimestamp(1, Timestamp.from(now));
                return claimed(stmt.executeQuery());
            } catch (SQLException e) {
                throw new DeadlineStorageException("Failed to claim due deadline: now=" + now, e);
            }
        });
    }

    @Override
    public Optional<ClaimedDeadline> claim(DeadlineId deadlineId) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT deadline_id, command_type, serialized_payload, serialized_metadata, due_at, error_count
                FROM smd_deadline_store
                WHERE deadline_id = ? AND status <> 'ABANDONED'
                FOR UPDATE SKIP LOCKED
                """)) {
                stmt.setObject(1, deadlineId.value());
                return claimed(stmt.executeQuery());
            } catch (SQLException e) {
                throw new DeadlineStorageException("Failed to claim deadline: deadlineId=" + deadlineId.value(), e);
            }
        });
    }

    @Override
    public Optional<DeadlineState> deadlineState(DeadlineId deadlineId) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT deadline_id, command_type, due_at, next_attempt_at, status, error_count, last_error_message, last_error_at
                FROM smd_deadline_store
                WHERE deadline_id = ?
                """)) {
                stmt.setObject(1, deadlineId.value());
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new DeadlineState(
                        new DeadlineId((UUID) rs.getObject("deadline_id")),
                        rs.getString("command_type"),
                        rs.getTimestamp("due_at").toInstant(),
                        rs.getTimestamp("next_attempt_at").toInstant(),
                        DeadlineStatus.valueOf(rs.getString("status")),
                        rs.getInt("error_count"),
                        Optional.ofNullable(rs.getString("last_error_message")),
                        optionalTimestamp(rs, "last_error_at")
                    ));
                }
            } catch (SQLException e) {
                throw new DeadlineStorageException("Failed to read deadline state: deadlineId=" + deadlineId.value(), e);
            }
        });
    }

    private Optional<ClaimedDeadline> claimed(ResultSet resultSet) throws SQLException {
        try (var rs = resultSet) {
            if (!rs.next()) {
                return Optional.empty();
            }
            var deadline = new SerializedDeadline(
                new DeadlineId((UUID) rs.getObject("deadline_id")),
                rs.getString("command_type"),
                rs.getBytes("serialized_payload"),
                rs.getBytes("serialized_metadata"),
                rs.getTimestamp("due_at").toInstant()
            );
            return Optional.of(new PostgresClaimedDeadline(deadline, rs.getInt("error_count"), connectionProvider));
        }
    }

    record PostgresClaimedDeadline(SerializedDeadline deadline,
                                   int errorCount,
                                   ConnectionProvider connectionProvider) implements ClaimedDeadline {

        PostgresClaimedDeadline {
            requireNonNull(deadline);
            requireNonNull(connectionProvider);
        }

        @Override
        public void markFired() {
            connectionProvider.doWithConnection(connection -> {
                try (var stmt = connection.prepareStatement("""
                    DELETE FROM smd_deadline_store
                    WHERE deadline_id = ?
                    """)) {
                    stmt.setObject(1, deadlineId());
                    if (stmt.executeUpdate() != 1) {
                        throw new DeadlineStorageException("Failed to remove fired deadline: deadlineId=" + deadlineId());
                    }
                } catch (SQLException e) {
                    throw new DeadlineStorageException("Failed to mark deadline fired: deadlineId=" + deadlineId(), e);
                }
            });
        }

        @Override
        public void markFailed(Exception exception, int errorCount, Instant failedAt, Instant nextAttemptAt) {
            connectionProvider.doWithConnection(connection -> {
                try (var stmt = connection.prepareStatement("""
                    UPDATE smd_deadline_store
                    SET status = 'FAILED',
                        error_count = ?,
                        next_attempt_at = ?,
                        last_error_message = ?,
                        last_error_at = ?
                    WHERE deadline_id = ?
                    """)) {
                    stmt.setInt(1, errorCount);
                    stmt.setTimestamp(2, Timestamp.from(nextAttemptAt));
                    stmt.setString(3, getStackTraceAsString(exception));
                    stmt.setTimestamp(4, Timestamp.from(failedAt));
                    stmt.setObject(5, deadlineId());
                    updateClaimedRow(stmt.executeUpdate());
                } catch (SQLException e) {
                    throw new DeadlineStorageException("Failed to mark deadline failed: deadlineId=" + deadlineId(), e);
                }
            });
        }

        @Override
        public void markAbandoned(Exception exception, Instant abandonedAt) {
            connectionProvider.doWithConnection(connection -> {
                try (var stmt = connection.prepareStatement("""
                    UPDATE smd_deadline_store
                    SET status = 'ABANDONED',
                        last_error_message = ?,
                        last_error_at = ?
                    WHERE deadline_id = ?
                    """)) {
                    stmt.setString(1, getStackTraceAsString(exception));
                    stmt.setTimestamp(2, Timestamp.from(abandonedAt));
                    stmt.setObject(3, deadlineId());
                    updateClaimedRow(stmt.executeUpdate());
                } catch (SQLException e) {
                    throw new DeadlineStorageException("Failed to mark deadline abandoned: deadlineId=" + deadlineId(), e);
                }
            });
        }

        private UUID deadlineId() {
            return deadline.deadlineId().value();
        }

        private void updateClaimedRow(int updatedRows) {
            if (updatedRows != 1) {
                throw new DeadlineStorageException("Claimed deadline no longer exists: deadlineId=" + deadlineId());
            }
        }
    }
}
