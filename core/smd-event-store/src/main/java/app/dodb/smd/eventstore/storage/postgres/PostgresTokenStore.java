package app.dodb.smd.eventstore.storage.postgres;

import app.dodb.smd.eventstore.storage.ConnectionProvider;
import app.dodb.smd.eventstore.storage.EventLog;
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
import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalLong;
import static app.dodb.smd.eventstore.storage.postgres.ResultSetValues.optionalTimestamp;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;

public class PostgresTokenStore implements TokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresTokenStore.class);

    private final ConnectionProvider connectionProvider;

    public PostgresTokenStore(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public Optional<Token> claimToken(EventLog log, String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedToken = claimTokenRow(connection, log, processingGroup);
            if (claimedToken.isPresent()) {
                return claimedToken;
            }

            if (tryClaimProcessingGroup(connection, log, processingGroup)) {
                createTokenRow(connection, log, processingGroup);
                return claimTokenRow(connection, log, processingGroup);
            }

            return empty();
        });
    }

    @Override
    public Optional<TokenState> tokenState(EventLog log, String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            try (var stmt = connection.prepareStatement("""
                SELECT processing_group,
                       last_processed_sequence_number,
                       last_gap_detected_at,
                       gap_sequence_number
                FROM smd_token_store
                WHERE log = ? AND processing_group = ?
                """)) {
                stmt.setString(1, log.name());
                stmt.setString(2, processingGroup);
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new TokenState(
                        rs.getString("processing_group"),
                        optionalLong(rs, "last_processed_sequence_number"),
                        optionalTimestamp(rs, "last_gap_detected_at"),
                        optionalLong(rs, "gap_sequence_number")
                    ));
                }
            } catch (SQLException e) {
                throw new TokenStoreException("Failed to read token state: log=" + log.name() + ", processingGroup=" + processingGroup, e);
            }
        });
    }

    private boolean tryClaimProcessingGroup(Connection connection, EventLog log, String processingGroup) {
        try (var stmt = connection.prepareStatement("""
            SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))
            """)) {
            stmt.setString(1, "smd_token_store#" + log.name());
            stmt.setString(2, processingGroup);

            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim token advisory lock: log=" + log.name() + ", processingGroup=" + processingGroup, e);
        }
    }

    private void createTokenRow(Connection connection, EventLog log, String processingGroup) {
        try (var stmt = connection.prepareStatement("""
            INSERT INTO smd_token_store (log, processing_group)
            VALUES (?, ?)
            ON CONFLICT (log, processing_group) DO NOTHING
            """)) {
            stmt.setString(1, log.name());
            stmt.setString(2, processingGroup);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize token: log=" + log.name() + ", processingGroup=" + processingGroup, e);
        }
    }

    private Optional<Token> claimTokenRow(Connection connection, EventLog log, String processingGroup) {
        try (var stmt = connection.prepareStatement("""
            SELECT last_processed_sequence_number, last_gap_detected_at
            FROM smd_token_store
            WHERE log = ? AND processing_group = ?
            FOR UPDATE SKIP LOCKED
            """)) {
            stmt.setString(1, log.name());
            stmt.setString(2, processingGroup);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return of(PostgresToken.from(log, processingGroup, connectionProvider, rs));
                }
                return empty();
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim token row: log=" + log.name() + ", processingGroup=" + processingGroup, e);
        }
    }

    record PostgresToken(EventLog log,
                         String processingGroup,
                         ConnectionProvider connectionProvider,
                         Optional<Long> lastProcessedSequenceNumber,
                         Optional<Instant> lastGapDetectedAt) implements Token {

        PostgresToken {
            requireNonNull(log);
            requireNonNull(processingGroup);
            requireNonNull(connectionProvider);
            requireNonNull(lastProcessedSequenceNumber);
            requireNonNull(lastGapDetectedAt);
        }

        static PostgresToken from(EventLog log, String processingGroup, ConnectionProvider connectionProvider, ResultSet rs) throws SQLException {
            return new PostgresToken(
                log,
                processingGroup,
                connectionProvider,
                optionalLong(rs, "last_processed_sequence_number"),
                optionalTimestamp(rs, "last_gap_detected_at")
            );
        }

        @Override
        public void markProcessed(long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement("""
                    UPDATE smd_token_store
                    SET last_processed_sequence_number = ?,
                        last_gap_detected_at = NULL,
                        gap_sequence_number = NULL
                    WHERE log = ? AND processing_group = ?
                    """)) {
                    stmt.setLong(1, sequenceNumber);
                    stmt.setString(2, log.name());
                    stmt.setString(3, processingGroup);
                    stmt.executeUpdate();
                    LOGGER.debug("Marked processed: log={}, processingGroup={}, sequenceNumber={}", log.name(), processingGroup, sequenceNumber);
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark processed: log=" + log.name() + ", processingGroup=" + processingGroup, e);
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
                    WHERE log = ? AND processing_group = ?
                    """)) {
                    stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                    stmt.setLong(2, sequenceNumber);
                    stmt.setString(3, log.name());
                    stmt.setString(4, processingGroup);
                    stmt.executeUpdate();
                    LOGGER.warn("Gap detected: log={}, processingGroup={}, gapSequenceNumber={}", log.name(), processingGroup, sequenceNumber);
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark gap detected: log=" + log.name() + ", processingGroup=" + processingGroup, e);
                }
            });
        }
    }
}
