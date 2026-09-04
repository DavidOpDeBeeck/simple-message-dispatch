package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.eventstore.sequence.EventSubjectSequence;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.storage.ConnectionProvider;
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
    public Optional<EventSubjectSequence> claimGlobal(String processingGroup) {
        return claim(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSubjectSequence> claimSubject(String processingGroup, String subjectId) {
        return claim(processingGroup, Optional.of(subjectId));
    }

    @Override
    public Optional<EventSequenceState> globalEventSequenceState(String processingGroup) {
        return eventSequenceState(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(String processingGroup, String subjectId) {
        return eventSequenceState(processingGroup, Optional.of(requireNonNull(subjectId)));
    }

    private Optional<EventSubjectSequence> claim(String processingGroup, Optional<String> subjectId) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedSequence = claimSequenceRow(connection, processingGroup, subjectId);
            if (claimedSequence.isPresent()) {
                return claimedSequence;
            }

            if (tryClaimSequence(connection, processingGroup, subjectId)) {
                createSequenceRow(connection, processingGroup, subjectId);
                return claimSequenceRow(connection, processingGroup, subjectId);
            }

            return Optional.empty();
        });
    }

    private Optional<EventSequenceState> eventSequenceState(String processingGroup, Optional<String> subjectId) {
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
                WHERE processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                """)) {
                stmt.setString(1, processingGroup);
                stmt.setString(2, subjectId.orElse(null));
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
                throw new TokenStoreException("Failed to read event sequence state: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
            }
        });
    }

    private boolean tryClaimSequence(Connection connection, String processingGroup, Optional<String> subjectId) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))
            """)) {
            stmt.setString(1, "smd_event_sequence_state#" + processingGroup);
            stmt.setString(2, subjectId.map(value -> "subject#" + value).orElse("global"));

            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim event sequence advisory lock: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
        }
    }

    private void createSequenceRow(Connection connection, String processingGroup, Optional<String> subjectId) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_event_sequence_state (processing_group, subject_id, status, error_count)
            VALUES (?, ?, 'ACTIVE', 0)
            ON CONFLICT (processing_group, subject_id) DO NOTHING
            """)) {
            stmt.setString(1, processingGroup);
            stmt.setString(2, subjectId.orElse(null));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize event sequence: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
        }
    }

    private Optional<EventSubjectSequence> claimSequenceRow(Connection connection, String processingGroup, Optional<String> subjectId) {
        try (var stmt = connection.prepareStatement("""
            SELECT processing_group, subject_id, status, last_processed_sequence_number, error_count, last_error_at
            FROM smd_event_sequence_state
            WHERE processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
            FOR UPDATE SKIP LOCKED
            """)) {
            stmt.setString(1, processingGroup);
            stmt.setString(2, subjectId.orElse(null));

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PostgresEventSubjectSequence(
                        rs.getString("processing_group"),
                        Optional.ofNullable(rs.getString("subject_id")),
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
            throw new TokenStoreException("Failed to claim event sequence row: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
        }
    }

    record PostgresEventSubjectSequence(String processingGroup,
                                        Optional<String> subjectId,
                                        EventSubjectSequenceStatus status,
                                        Optional<Long> lastProcessedSequenceNumber,
                                        int errorCount,
                                        Optional<Instant> lastErrorAt,
                                        ConnectionProvider connectionProvider) implements EventSubjectSequence {

        PostgresEventSubjectSequence {
            requireNonNull(processingGroup);
            requireNonNull(subjectId);
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
                    WHERE processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    stmt.setString(2, processingGroup);
                    stmt.setString(3, subjectId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence processed: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
                }
            });
        }

        @Override
        public void markSkipped(long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET last_processed_sequence_number = ?
                    WHERE processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    stmt.setString(2, processingGroup);
                    stmt.setString(3, subjectId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence skipped: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
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
                    WHERE processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    var now = Timestamp.from(Instant.now());
                    stmt.setInt(1, errorCount);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, now);
                    stmt.setString(4, processingGroup);
                    stmt.setString(5, subjectId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence failed: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
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
                    WHERE processing_group = ? AND subject_id IS NOT DISTINCT FROM ?
                    """)) {
                    var now = Timestamp.from(Instant.now());
                    stmt.setLong(1, sequenceNumber);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, now);
                    stmt.setString(4, processingGroup);
                    stmt.setString(5, subjectId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence abandoned: processingGroup=" + processingGroup + ", subjectId=" + subjectId.orElse(null), e);
                }
            });
        }
    }
}
