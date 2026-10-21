package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.Token;
import app.dodb.smd.eventstore.storage.TokenState;
import app.dodb.smd.eventstore.storage.TokenStore;
import app.dodb.smd.eventstore.storage.TokenStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalLong;
import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalTimestamp;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;

public class PostgresTokenStore implements TokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresTokenStore.class);
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final ConnectionProvider connectionProvider;

    public PostgresTokenStore(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public Optional<Token> claimToken(String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedToken = claimTokenRow(connection, processingGroup);
            if (claimedToken.isPresent()) {
                return claimedToken;
            }

            if (tryClaimProcessingGroup(connection, processingGroup)) {
                createTokenRow(connection, processingGroup);
                return claimTokenRow(connection, processingGroup);
            }

            return empty();
        });
    }

    @Override
    public Optional<Token> claimTokenForReset(String processingGroup, Duration lockTimeout) {
        return connectionProvider.doWithConnection(connection -> {
            try {
                // A lock timeout aborts the transaction; rolling back to the savepoint keeps it usable for the caller.
                var savepoint = connection.setSavepoint();
                try {
                    var previousLockTimeout = currentLockTimeout(connection);
                    setLocalLockTimeout(connection, lockTimeout.toMillis() + "ms");
                    claimProcessingGroup(connection, processingGroup);
                    createTokenRow(connection, processingGroup);
                    var claimedToken = lockTokenRow(connection, processingGroup);
                    setLocalLockTimeout(connection, previousLockTimeout);
                    connection.releaseSavepoint(savepoint);
                    return Optional.<Token>of(claimedToken);
                } catch (SQLException e) {
                    if (!LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                        throw e;
                    }
                    connection.rollback(savepoint);
                    return Optional.<Token>empty();
                }
            } catch (SQLException e) {
                throw new TokenStoreException("Failed to claim token for reset of processing group: " + processingGroup, e);
            }
        });
    }

    @Override
    public Optional<TokenState> tokenState(String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT processing_group,
                       last_processed_sequence_number,
                       last_gap_detected_at,
                       gap_sequence_number,
                       replay_until_sequence_number
                FROM smd_token_store
                WHERE processing_group = ?
                """)) {
                stmt.setString(1, processingGroup);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new TokenState(
                        rs.getString("processing_group"),
                        optionalLong(rs, "last_processed_sequence_number"),
                        optionalTimestamp(rs, "last_gap_detected_at"),
                        optionalLong(rs, "gap_sequence_number"),
                        optionalLong(rs, "replay_until_sequence_number")
                    ));
                }
            } catch (SQLException e) {
                throw new TokenStoreException("Failed to read token state for processing group: " + processingGroup, e);
            }
        });
    }

    private boolean tryClaimProcessingGroup(Connection connection, String processingGroup) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_try_advisory_xact_lock(hashtext('smd_token_store'), hashtext(?))
            """)) {
            stmt.setString(1, processingGroup);

            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim token advisory lock for processing group: " + processingGroup, e);
        }
    }

    private void claimProcessingGroup(Connection connection, String processingGroup) throws SQLException {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_advisory_xact_lock(hashtext('smd_token_store'), hashtext(?))
            """)) {
            stmt.setString(1, processingGroup);
            stmt.execute();
        }
    }

    private static String currentLockTimeout(Connection connection) throws SQLException {
        try (var stmt = connection.prepareStatement("SELECT current_setting('lock_timeout')");
             var rs = stmt.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void setLocalLockTimeout(Connection connection, String lockTimeout) throws SQLException {
        try (var stmt = connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)")) {
            stmt.setString(1, lockTimeout);
            stmt.execute();
        }
    }

    private PostgresToken lockTokenRow(Connection connection, String processingGroup) throws SQLException {
        try (var stmt = connection.prepareStatement("""
            SELECT last_processed_sequence_number, last_gap_detected_at, replay_until_sequence_number
            FROM smd_token_store
            WHERE processing_group = ?
            FOR UPDATE
            """)) {
            stmt.setString(1, processingGroup);

            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new TokenStoreException("Token row missing after initialization for processing group: " + processingGroup);
                }
                return PostgresToken.from(processingGroup, connectionProvider, rs);
            }
        }
    }

    private void createTokenRow(Connection connection, String processingGroup) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_token_store (processing_group)
            VALUES (?)
            ON CONFLICT (processing_group) DO NOTHING
            """)) {
            stmt.setString(1, processingGroup);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize token for processing group: " + processingGroup, e);
        }
    }

    private Optional<Token> claimTokenRow(Connection connection, String processingGroup) {
        try (var stmt = connection.prepareStatement("""
            SELECT last_processed_sequence_number, last_gap_detected_at, replay_until_sequence_number
            FROM smd_token_store
            WHERE processing_group = ?
            FOR UPDATE SKIP LOCKED
            """)) {
            stmt.setString(1, processingGroup);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return of(PostgresToken.from(processingGroup, connectionProvider, rs));
                }
                return empty();
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim token row for processing group: " + processingGroup, e);
        }
    }

    record PostgresToken(String processingGroup,
                         ConnectionProvider connectionProvider,
                         Optional<Long> lastProcessedSequenceNumber,
                         Optional<Instant> lastGapDetectedAt,
                         Optional<Long> replayUntilSequenceNumber) implements Token {

        PostgresToken {
            requireNonNull(processingGroup);
            requireNonNull(connectionProvider);
            requireNonNull(lastProcessedSequenceNumber);
            requireNonNull(lastGapDetectedAt);
            requireNonNull(replayUntilSequenceNumber);
        }

        static PostgresToken from(String processingGroup, ConnectionProvider connectionProvider, ResultSet rs) throws SQLException {
            return new PostgresToken(
                processingGroup,
                connectionProvider,
                optionalLong(rs, "last_processed_sequence_number"),
                optionalTimestamp(rs, "last_gap_detected_at"),
                optionalLong(rs, "replay_until_sequence_number")
            );
        }

        @Override
        public void markProcessed(long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_token_store
                    SET last_processed_sequence_number = ?,
                        last_gap_detected_at = NULL,
                        gap_sequence_number = NULL,
                        replay_until_sequence_number = CASE
                            WHEN replay_until_sequence_number > ? THEN replay_until_sequence_number
                        END
                    WHERE processing_group = ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    stmt.setLong(2, sequenceNumber);
                    stmt.setString(3, processingGroup);
                    stmt.executeUpdate();
                    LOGGER.debug("Marked processed: processingGroup={}, sequenceNumber={}", processingGroup, sequenceNumber);
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark processed for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public void markGapDetected(long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_token_store
                    SET last_gap_detected_at = ?,
                        gap_sequence_number = ?
                    WHERE processing_group = ?
                    """)) {
                    stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                    stmt.setLong(2, sequenceNumber);
                    stmt.setString(3, processingGroup);
                    stmt.executeUpdate();
                    LOGGER.warn("Gap detected: processingGroup={}, gapSequenceNumber={}", processingGroup, sequenceNumber);
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark gap detected for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public void markReset(Optional<Long> replayUntilSequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_token_store
                    SET last_processed_sequence_number = 0,
                        last_gap_detected_at = NULL,
                        gap_sequence_number = NULL,
                        replay_until_sequence_number = ?
                    WHERE processing_group = ?
                    """)) {
                    stmt.setObject(1, replayUntilSequenceNumber.orElse(null), Types.BIGINT);
                    stmt.setString(2, processingGroup);
                    if (stmt.executeUpdate() != 1) {
                        throw new TokenStoreException("Token row missing while resetting processing group: " + processingGroup);
                    }
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to reset token for processing group: " + processingGroup, e);
                }
            });
        }
    }
}
