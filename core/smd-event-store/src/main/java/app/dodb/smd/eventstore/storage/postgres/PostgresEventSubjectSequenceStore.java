package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.eventstore.sequence.EventSubjectSequence;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.EventLog;
import app.dodb.smd.eventstore.storage.TokenStoreException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalLong;
import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalTimestamp;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;

public class PostgresEventSubjectSequenceStore implements EventSubjectSequenceStore {

    private final ConnectionProvider connectionProvider;

    public PostgresEventSubjectSequenceStore(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public Optional<EventSubjectSequence> claimGlobal(EventLog log, String processingGroup) {
        return claim(new SequenceKey(log, processingGroup, Optional.empty()));
    }

    @Override
    public Optional<EventSubjectSequence> claimSubject(EventLog log, String processingGroup, String subjectId) {
        return claim(new SequenceKey(log, processingGroup, Optional.of(subjectId)));
    }

    @Override
    public Optional<EventSequenceState> globalEventSequenceState(EventLog log, String processingGroup) {
        return eventSequenceState(new SequenceKey(log, processingGroup, Optional.empty()));
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(EventLog log, String processingGroup, String subjectId) {
        return eventSequenceState(new SequenceKey(log, processingGroup, Optional.of(requireNonNull(subjectId))));
    }

    private Optional<EventSubjectSequence> claim(SequenceKey key) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedSequence = claimSequenceRow(connection, key);
            if (claimedSequence.isPresent()) {
                return claimedSequence;
            }

            if (tryClaimSequence(connection, key)) {
                createSequenceRow(connection, key);
                return claimSequenceRow(connection, key);
            }

            return Optional.empty();
        });
    }

    private Optional<EventSequenceState> eventSequenceState(SequenceKey key) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT processing_group,
                       subject_id,
                       status,
                       last_processed_sequence_number,
                       error_count,
                       last_error_message,
                       last_error_at
                FROM smd_event_sequence_state
                WHERE log = ? AND processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                """)) {
                key.setParameters(stmt, 1);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new EventSequenceState(
                        rs.getString("processing_group"),
                        Optional.ofNullable(rs.getString("subject_id")),
                        EventSubjectSequenceStatus.valueOf(rs.getString("status")),
                        optionalLong(rs, "last_processed_sequence_number"),
                        rs.getInt("error_count"),
                        Optional.ofNullable(rs.getString("last_error_message")),
                        optionalTimestamp(rs, "last_error_at")
                    ));
                }
            } catch (SQLException e) {
                throw new TokenStoreException("Failed to read event sequence state: " + key, e);
            }
        });
    }

    private boolean tryClaimSequence(Connection connection, SequenceKey key) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))
            """)) {
            stmt.setString(1, "smd_event_sequence_state#" + key.log().name() + "#" + key.processingGroup());
            stmt.setString(2, key.subjectId().map(value -> "subject#" + value).orElse("global"));

            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim event sequence advisory lock: " + key, e);
        }
    }

    private void createSequenceRow(Connection connection, SequenceKey key) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_event_sequence_state (log, processing_group, subject_id, status, error_count)
            VALUES (?, ?, ?, 'ACTIVE', 0)
            ON CONFLICT (log, processing_group, subject_id) DO NOTHING
            """)) {
            key.setParameters(stmt, 1);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize event sequence: " + key, e);
        }
    }

    private Optional<EventSubjectSequence> claimSequenceRow(Connection connection, SequenceKey key) {
        try (var stmt = connection.prepareStatement("""
            SELECT status, last_processed_sequence_number, error_count, last_error_at
            FROM smd_event_sequence_state
            WHERE log = ? AND processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
            FOR UPDATE SKIP LOCKED
            """)) {
            key.setParameters(stmt, 1);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PostgresEventSubjectSequence(
                        key,
                        EventSubjectSequenceStatus.valueOf(rs.getString("status")),
                        optionalLong(rs, "last_processed_sequence_number"),
                        rs.getInt("error_count"),
                        Optional.ofNullable(rs.getTimestamp("last_error_at")).map(Timestamp::toInstant),
                        connectionProvider
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim event sequence row: " + key, e);
        }
    }

    private record SequenceKey(EventLog log, String processingGroup, Optional<String> subjectId) {

        SequenceKey {
            requireNonNull(log);
            requireNonNull(processingGroup);
            requireNonNull(subjectId);
        }

        void setParameters(PreparedStatement stmt, int firstIndex) throws SQLException {
            stmt.setString(firstIndex, log.name());
            stmt.setString(firstIndex + 1, processingGroup);
            stmt.setString(firstIndex + 2, subjectId.orElse(null));
        }

        @Override
        public String toString() {
            return "log=" + log.name() + ", processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null);
        }
    }

    private record PostgresEventSubjectSequence(SequenceKey key,
                                                EventSubjectSequenceStatus status,
                                                Optional<Long> lastProcessedSequenceNumber,
                                                int errorCount,
                                                Optional<Instant> lastErrorAt,
                                                ConnectionProvider connectionProvider) implements EventSubjectSequence {

        PostgresEventSubjectSequence {
            requireNonNull(key);
            requireNonNull(status);
            requireNonNull(lastProcessedSequenceNumber);
            requireNonNull(lastErrorAt);
            requireNonNull(connectionProvider);
        }

        @Override
        public void markProcessed(long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET status = 'ACTIVE',
                        last_processed_sequence_number = ?,
                        error_count = 0,
                        last_error_message = NULL,
                        last_error_at = NULL
                    WHERE log = ? AND processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    key.setParameters(stmt, 2);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence processed: " + key, e);
                }
            });
        }

        @Override
        public void markSkipped(long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET last_processed_sequence_number = ?
                    WHERE log = ? AND processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    key.setParameters(stmt, 2);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence skipped: " + key, e);
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
                    WHERE log = ? AND processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setInt(1, errorCount);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()));
                    key.setParameters(stmt, 4);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence failed: " + key, e);
                }
            });
        }

        @Override
        public void markAbandoned(long sequenceNumber, Exception exception) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET status = 'ABANDONED',
                        last_processed_sequence_number = ?,
                        error_count = 0,
                        last_error_message = ?,
                        last_error_at = ?
                    WHERE log = ? AND processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()));
                    key.setParameters(stmt, 4);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence abandoned: " + key, e);
                }
            });
        }
    }
}
