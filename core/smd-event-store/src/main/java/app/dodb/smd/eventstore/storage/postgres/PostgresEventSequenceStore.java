package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.eventstore.processing.EventSequence;
import app.dodb.smd.eventstore.processing.EventSequenceId;
import app.dodb.smd.eventstore.processing.EventSequenceState;
import app.dodb.smd.eventstore.processing.EventSequenceStatus;
import app.dodb.smd.eventstore.processing.EventSequenceStore;
import app.dodb.smd.eventstore.processing.TokenStoreException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalLong;
import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalTimestamp;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

public class PostgresEventSequenceStore implements EventSequenceStore {

    private final ConnectionProvider connectionProvider;

    public PostgresEventSequenceStore(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public Optional<EventSequence> claim(String processingGroup, EventSequenceId sequenceId) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedSequence = claimSequenceRow(connection, processingGroup, sequenceId);
            if (claimedSequence.isPresent()) {
                return claimedSequence;
            }

            if (tryClaimSequence(connection, processingGroup, sequenceId)) {
                createSequenceRow(connection, processingGroup, sequenceId);
                return claimSequenceRow(connection, processingGroup, sequenceId);
            }

            return Optional.empty();
        });
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(String processingGroup, EventSequenceId sequenceId) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT processing_group,
                       subject_type,
                       subject_id,
                       status,
                       last_processed_position,
                       error_count,
                       last_error_message,
                       last_error_at
                FROM smd_event_sequence_state
                WHERE processing_group = ? AND %s
                """.formatted(sequenceCondition(sequenceId)))) {
                stmt.setString(1, processingGroup);
                setSequenceCondition(stmt, 2, sequenceId);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new EventSequenceState(
                        rs.getString("processing_group"),
                        sequenceId(rs),
                        EventSequenceStatus.valueOf(rs.getString("status")),
                        optionalLong(rs, "last_processed_position"),
                        rs.getInt("error_count"),
                        Optional.ofNullable(rs.getString("last_error_message")),
                        optionalTimestamp(rs, "last_error_at")
                    ));
                }
            } catch (SQLException e) {
                throw new TokenStoreException("Failed to read event sequence state: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
            }
        });
    }

    private boolean tryClaimSequence(Connection connection, String processingGroup, EventSequenceId sequenceId) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))
            """)) {
            stmt.setString(1, "smd_event_sequence_state#" + processingGroup);
            stmt.setString(2, lockKey(sequenceId));

            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim event sequence advisory lock: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
        }
    }

    private void createSequenceRow(Connection connection, String processingGroup, EventSequenceId sequenceId) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_event_sequence_state (processing_group, subject_type, subject_id, status, error_count)
            VALUES (?, ?, ?, 'ACTIVE', 0)
            ON CONFLICT (processing_group, subject_type, subject_id) DO NOTHING
            """)) {
            stmt.setString(1, processingGroup);
            setSequenceValues(stmt, 2, sequenceId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize event sequence: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
        }
    }

    private Optional<EventSequence> claimSequenceRow(Connection connection, String processingGroup, EventSequenceId sequenceId) {
        try (var stmt = connection.prepareStatement("""
            SELECT processing_group, subject_type, subject_id, status, last_processed_position, error_count, last_error_at
            FROM smd_event_sequence_state
            WHERE processing_group = ? AND %s
            FOR UPDATE SKIP LOCKED
            """.formatted(sequenceCondition(sequenceId)))) {
            stmt.setString(1, processingGroup);
            setSequenceCondition(stmt, 2, sequenceId);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PostgresEventSequence(
                        rs.getString("processing_group"),
                        sequenceId(rs),
                        EventSequenceStatus.valueOf(rs.getString("status")),
                        optionalLong(rs, "last_processed_position"),
                        rs.getInt("error_count"),
                        Optional.ofNullable(rs.getTimestamp("last_error_at")).map(Timestamp::toInstant),
                        connectionProvider
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim event sequence row: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
        }
    }

    private static String sequenceCondition(EventSequenceId sequenceId) {
        return switch (sequenceId) {
            case EventSequenceId.OfSubject _ -> "subject_type = ? AND subject_id = ?";
            case EventSequenceId.Global _ -> "subject_type IS NULL AND subject_id IS NULL";
        };
    }

    private static void setSequenceCondition(PreparedStatement stmt, int index, EventSequenceId sequenceId) throws SQLException {
        if (sequenceId instanceof EventSequenceId.OfSubject(var subject)) {
            stmt.setString(index, subject.type());
            stmt.setString(index + 1, subject.id());
        }
    }

    private static void setSequenceValues(PreparedStatement stmt, int index, EventSequenceId sequenceId) throws SQLException {
        switch (sequenceId) {
            case EventSequenceId.OfSubject(var subject) -> {
                stmt.setString(index, subject.type());
                stmt.setString(index + 1, subject.id());
            }
            case EventSequenceId.Global _ -> {
                stmt.setNull(index, Types.VARCHAR);
                stmt.setNull(index + 1, Types.VARCHAR);
            }
        }
    }

    private static EventSequenceId sequenceId(ResultSet rs) throws SQLException {
        var subjectId = rs.getString("subject_id");
        if (subjectId == null) {
            return new EventSequenceId.Global();
        }
        return new EventSequenceId.OfSubject(new Subject(rs.getString("subject_type"), subjectId));
    }

    private static String lockKey(EventSequenceId sequenceId) {
        return switch (sequenceId) {
            case EventSequenceId.OfSubject(var subject) -> "subject#" + subject.type() + "#" + subject.id();
            case EventSequenceId.Global _ -> "global";
        };
    }

    record PostgresEventSequence(String processingGroup,
                                 EventSequenceId sequenceId,
                                 EventSequenceStatus status,
                                 Optional<Long> lastProcessedPosition,
                                 int errorCount,
                                 Optional<Instant> lastErrorAt,
                                 ConnectionProvider connectionProvider) implements EventSequence {

        PostgresEventSequence {
            requireNonNull(processingGroup);
            requireNonNull(sequenceId);
            requireNonNull(status);
            requireNonNull(lastProcessedPosition);
            requireNonNull(lastErrorAt);
            requireNonNull(connectionProvider);
        }

        @Override
        public void markProcessed(long globalPosition) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET status = 'ACTIVE',
                        last_processed_position = ?,
                        error_count = 0,
                        last_error_message = NULL,
                        last_error_at = NULL
                    WHERE processing_group = ? AND %s
                    """.formatted(sequenceCondition(sequenceId)))) {
                    stmt.setLong(1, globalPosition);
                    stmt.setString(2, processingGroup);
                    setSequenceCondition(stmt, 3, sequenceId);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence processed: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
                }
            });
        }

        @Override
        public void markSkipped(long globalPosition) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET last_processed_position = ?
                    WHERE processing_group = ? AND %s
                    """.formatted(sequenceCondition(sequenceId)))) {
                    stmt.setLong(1, globalPosition);
                    stmt.setString(2, processingGroup);
                    setSequenceCondition(stmt, 3, sequenceId);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence skipped: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
                }
            });
        }

        @Override
        public void markFailed(Exception exception, int errorCount) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET status = 'FAILED',
                        error_count = ?,
                        last_error_message = ?,
                        last_error_at = ?
                    WHERE processing_group = ? AND %s
                    """.formatted(sequenceCondition(sequenceId)))) {
                    var now = Timestamp.from(Instant.now());
                    stmt.setInt(1, errorCount);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, now);
                    stmt.setString(4, processingGroup);
                    setSequenceCondition(stmt, 5, sequenceId);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence failed: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
                }
            });
        }

        @Override
        public void markAbandoned(long globalPosition, Exception exception) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET status = 'ABANDONED',
                        last_processed_position = ?,
                        error_count = 0,
                        last_error_message = ?,
                        last_error_at = ?
                    WHERE processing_group = ? AND %s
                    """.formatted(sequenceCondition(sequenceId)))) {
                    var now = Timestamp.from(Instant.now());
                    stmt.setLong(1, globalPosition);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, now);
                    stmt.setString(4, processingGroup);
                    setSequenceCondition(stmt, 5, sequenceId);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence abandoned: processingGroup=" + processingGroup + ", sequence=" + sequenceId, e);
                }
            });
        }
    }
}
