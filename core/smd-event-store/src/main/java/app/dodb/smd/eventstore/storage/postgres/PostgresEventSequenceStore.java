package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.api.framework.ConnectionProvider;
import app.dodb.smd.eventstore.sequence.EventSequence;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.sequence.EventSequenceStatus;
import app.dodb.smd.eventstore.sequence.EventSequenceStore;
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

public class PostgresEventSequenceStore implements EventSequenceStore {

    private final ConnectionProvider connectionProvider;

    public PostgresEventSequenceStore(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public Optional<EventSequence> claimGlobal(String processingGroup) {
        return claim(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSequence> claimAggregate(String processingGroup, String aggregateId) {
        return claim(processingGroup, Optional.of(aggregateId));
    }

    @Override
    public Optional<EventSequenceState> globalEventSequenceState(String processingGroup) {
        return eventSequenceState(processingGroup, Optional.empty());
    }

    @Override
    public Optional<EventSequenceState> eventSequenceState(String processingGroup, String aggregateId) {
        return eventSequenceState(processingGroup, Optional.of(requireNonNull(aggregateId)));
    }

    private Optional<EventSequence> claim(String processingGroup, Optional<String> aggregateId) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedSequence = claimSequenceRow(connection, processingGroup, aggregateId);
            if (claimedSequence.isPresent()) {
                return claimedSequence;
            }

            if (tryClaimSequence(connection, processingGroup, aggregateId)) {
                createSequenceRow(connection, processingGroup, aggregateId);
                return claimSequenceRow(connection, processingGroup, aggregateId);
            }

            return Optional.empty();
        });
    }

    private Optional<EventSequenceState> eventSequenceState(String processingGroup, Optional<String> aggregateId) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT processing_group,
                       aggregate_id,
                       status,
                       last_processed_position,
                       error_count,
                       last_error_message,
                       last_error_at
                FROM smd_event_sequence_state
                WHERE processing_group = ? AND aggregate_id IS NOT DISTINCT FROM ?
                """)) {
                stmt.setString(1, processingGroup);
                stmt.setString(2, aggregateId.orElse(null));
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new EventSequenceState(
                        rs.getString("processing_group"),
                        Optional.ofNullable(rs.getString("aggregate_id")),
                        EventSequenceStatus.valueOf(rs.getString("status")),
                        optionalLong(rs, "last_processed_position"),
                        rs.getInt("error_count"),
                        Optional.ofNullable(rs.getString("last_error_message")),
                        optionalTimestamp(rs, "last_error_at")
                    ));
                }
            } catch (SQLException e) {
                throw new TokenStoreException("Failed to read event sequence state: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
            }
        });
    }

    private boolean tryClaimSequence(Connection connection, String processingGroup, Optional<String> aggregateId) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))
            """)) {
            stmt.setString(1, "smd_event_sequence_state#" + processingGroup);
            stmt.setString(2, aggregateId.map(value -> "aggregate#" + value).orElse("global"));

            try (var rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim event sequence advisory lock: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
        }
    }

    private void createSequenceRow(Connection connection, String processingGroup, Optional<String> aggregateId) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_event_sequence_state (processing_group, aggregate_id, status, error_count)
            VALUES (?, ?, 'ACTIVE', 0)
            ON CONFLICT (processing_group, aggregate_id) DO NOTHING
            """)) {
            stmt.setString(1, processingGroup);
            stmt.setString(2, aggregateId.orElse(null));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize event sequence: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
        }
    }

    private Optional<EventSequence> claimSequenceRow(Connection connection, String processingGroup, Optional<String> aggregateId) {
        try (var stmt = connection.prepareStatement("""
            SELECT processing_group, aggregate_id, status, last_processed_position, error_count, last_error_at
            FROM smd_event_sequence_state
            WHERE processing_group = ? AND aggregate_id IS NOT DISTINCT FROM ?
            FOR UPDATE SKIP LOCKED
            """)) {
            stmt.setString(1, processingGroup);
            stmt.setString(2, aggregateId.orElse(null));

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new PostgresEventSequence(
                        rs.getString("processing_group"),
                        Optional.ofNullable(rs.getString("aggregate_id")),
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
            throw new TokenStoreException("Failed to claim event sequence row: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
        }
    }

    record PostgresEventSequence(String processingGroup,
                                        Optional<String> aggregateId,
                                        EventSequenceStatus status,
                                        Optional<Long> lastProcessedPosition,
                                        int errorCount,
                                        Optional<Instant> lastErrorAt,
                                        ConnectionProvider connectionProvider) implements EventSequence {

        PostgresEventSequence {
            requireNonNull(processingGroup);
            requireNonNull(aggregateId);
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
                    WHERE processing_group = ? AND aggregate_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, globalPosition);
                    stmt.setString(2, processingGroup);
                    stmt.setString(3, aggregateId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence processed: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
                }
            });
        }

        @Override
        public void markSkipped(long globalPosition) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_event_sequence_state
                    SET last_processed_position = ?
                    WHERE processing_group = ? AND aggregate_id IS NOT DISTINCT FROM ?
                    """)) {
                    stmt.setLong(1, globalPosition);
                    stmt.setString(2, processingGroup);
                    stmt.setString(3, aggregateId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence skipped: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
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
                    WHERE processing_group = ? AND aggregate_id IS NOT DISTINCT FROM ?
                    """)) {
                    var now = Timestamp.from(Instant.now());
                    stmt.setInt(1, errorCount);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, now);
                    stmt.setString(4, processingGroup);
                    stmt.setString(5, aggregateId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence failed: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
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
                    WHERE processing_group = ? AND aggregate_id IS NOT DISTINCT FROM ?
                    """)) {
                    var now = Timestamp.from(Instant.now());
                    stmt.setLong(1, globalPosition);
                    stmt.setString(2, getStackTraceAsString(exception));
                    stmt.setTimestamp(3, now);
                    stmt.setString(4, processingGroup);
                    stmt.setString(5, aggregateId.orElse(null));
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark event sequence abandoned: processingGroup=" + processingGroup + ", aggregateId=" + aggregateId.orElse(null), e);
                }
            });
        }
    }
}
