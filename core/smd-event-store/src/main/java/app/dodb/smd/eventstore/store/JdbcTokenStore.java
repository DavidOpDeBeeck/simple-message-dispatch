package app.dodb.smd.eventstore.store;

import app.dodb.smd.eventstore.framework.ConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;

public class JdbcTokenStore implements TokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcTokenStore.class);

    private final ConnectionProvider connectionProvider;

    public JdbcTokenStore(ConnectionProvider connectionProvider) {
        this.connectionProvider = requireNonNull(connectionProvider);
    }

    @Override
    public Optional<Token> claimToken(String processingGroup) {
        return connectionProvider.doWithConnection(connection -> {
            var claimedToken = claimTokenRow(connection, processingGroup);
            if (claimedToken.isPresent()) {
                return claimedToken;
            }

            if (!tryClaimProcessingGroup(connection, processingGroup)) {
                return empty();
            }

            createTokenRow(connection, processingGroup);
            return claimTokenRow(connection, processingGroup);
        });
    }

    private boolean tryClaimProcessingGroup(Connection connection, String processingGroup) {
        try (PreparedStatement stmt = connection.prepareStatement("""
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

    private void createTokenRow(Connection connection, String processingGroup) {
        try (PreparedStatement stmt = connection.prepareStatement("""
            INSERT INTO smd_token_store (processing_group, last_updated_at, error_count)
            VALUES (?, ?, 0)
            ON CONFLICT (processing_group) DO NOTHING
            """)) {
            stmt.setString(1, processingGroup);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to initialize token for processing group: " + processingGroup, e);
        }
    }

    private Optional<Token> claimTokenRow(Connection connection, String processingGroup) {
        try (PreparedStatement stmt = connection.prepareStatement("""
            SELECT last_processed_message_id, last_processed_sequence_number, last_failed_message_id,
                   error_count, last_error_at, last_gap_detected_at, gap_sequence_number
            FROM smd_token_store
            WHERE processing_group = ?
            FOR UPDATE SKIP LOCKED
            """)) {
            stmt.setString(1, processingGroup);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return of(PostgresToken.from(processingGroup, connectionProvider, rs));
                }
                return empty();
            }
        } catch (SQLException e) {
            throw new TokenStoreException("Failed to claim token row for processing group: " + processingGroup, e);
        }
    }

    static class PostgresToken implements Token {

        private static final String UPSERT_TOKEN = """
            INSERT INTO smd_token_store
                (processing_group, last_processed_sequence_number, last_updated_at, error_count, last_gap_detected_at, gap_sequence_number)
            VALUES (?, ?, ?, 0, NULL, NULL)
            ON CONFLICT (processing_group)
            DO UPDATE SET
                last_processed_sequence_number = EXCLUDED.last_processed_sequence_number,
                last_updated_at = EXCLUDED.last_updated_at,
                error_count = 0,
                last_gap_detected_at = NULL,
                gap_sequence_number = NULL
            WHERE smd_token_store.last_processed_sequence_number IS NULL
               OR EXCLUDED.last_processed_sequence_number >= smd_token_store.last_processed_sequence_number
            """;

        private static final String UPDATE_FAILED = """
            INSERT INTO smd_token_store
                (processing_group, last_updated_at, error_count, last_error_message, last_error_at)
            VALUES (?, ?, 1, ?, ?)
            ON CONFLICT (processing_group)
            DO UPDATE SET
                error_count = smd_token_store.error_count + 1,
                last_error_message = EXCLUDED.last_error_message,
                last_error_at = EXCLUDED.last_error_at,
                last_updated_at = EXCLUDED.last_updated_at
            """;

        private static final String MARK_GAP_DETECTED = """
            INSERT INTO smd_token_store
                (processing_group, last_gap_detected_at, gap_sequence_number, last_updated_at, error_count)
            VALUES (?, ?, ?, ?, 0)
            ON CONFLICT (processing_group)
            DO UPDATE SET
                last_gap_detected_at = EXCLUDED.last_gap_detected_at,
                gap_sequence_number = EXCLUDED.gap_sequence_number,
                last_updated_at = EXCLUDED.last_updated_at
            """;

        private final String processingGroup;
        private final ConnectionProvider connectionProvider;
        private final Optional<Long> lastProcessedSequenceNumber;
        private final int errorCount;
        private final Instant lastErrorAt;
        private final Instant lastGapDetectedAt;

        PostgresToken(String processingGroup,
                      ConnectionProvider connectionProvider,
                      Optional<Long> lastProcessedSequenceNumber,
                      int errorCount,
                      Instant lastErrorAt,
                      Instant lastGapDetectedAt) {
            this.processingGroup = requireNonNull(processingGroup);
            this.connectionProvider = requireNonNull(connectionProvider);
            this.lastProcessedSequenceNumber = requireNonNull(lastProcessedSequenceNumber);
            this.errorCount = errorCount;
            this.lastErrorAt = lastErrorAt;
            this.lastGapDetectedAt = lastGapDetectedAt;
        }

        static PostgresToken from(String processingGroup, ConnectionProvider connectionProvider, ResultSet rs) throws SQLException {
            long sequenceNumber = rs.getLong("last_processed_sequence_number");
            Optional<Long> lastProcessedSequenceNumber = rs.wasNull() ? empty() : of(sequenceNumber);

            Timestamp lastErrorAt = rs.getTimestamp("last_error_at");
            Timestamp lastGapDetectedAt = rs.getTimestamp("last_gap_detected_at");

            return new PostgresToken(
                processingGroup,
                connectionProvider,
                lastProcessedSequenceNumber,
                rs.getInt("error_count"),
                lastErrorAt == null ? null : lastErrorAt.toInstant(),
                lastGapDetectedAt == null ? null : lastGapDetectedAt.toInstant()
            );
        }

        @Override
        public Optional<Long> lastProcessedSequenceNumber() {
            return lastProcessedSequenceNumber;
        }

        @Override
        public int errorCount() {
            return errorCount;
        }

        @Override
        public Instant lastErrorAt() {
            return lastErrorAt;
        }

        @Override
        public Instant lastGapDetectedAt() {
            return lastGapDetectedAt;
        }

        @Override
        public void markProcessed(Long sequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(UPSERT_TOKEN)) {
                    stmt.setString(1, processingGroup);
                    stmt.setLong(2, sequenceNumber);
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()));

                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark processed for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public void markFailed(Long sequenceNumber, Exception exception) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(UPDATE_FAILED)) {
                    stmt.setString(1, processingGroup);
                    stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                    stmt.setString(3, getStackTraceAsString(exception));
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                    stmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark failed for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public void markAbandoned(Long sequenceNumber, Exception exception) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(UPDATE_FAILED)) {
                    stmt.setString(1, processingGroup);
                    stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                    stmt.setString(3, getStackTraceAsString(exception));
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                    stmt.executeUpdate();
                    LOGGER.error("Marked abandoned: processingGroup={}, sequenceNumber={}, error={}", processingGroup, sequenceNumber, exception.getMessage(), exception);
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark abandoned for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public void markGapDetected(Long gapSequenceNumber) {
            connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(MARK_GAP_DETECTED)) {
                    stmt.setString(1, processingGroup);
                    stmt.setTimestamp(2, Timestamp.from(Instant.now()));
                    stmt.setLong(3, gapSequenceNumber);
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));

                    stmt.executeUpdate();
                    LOGGER.warn("Gap detected: processingGroup={}, gapSequenceNumber={}", processingGroup, gapSequenceNumber);
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to mark gap detected for processing group: " + processingGroup, e);
                }
            });
        }

    }

    static class TokenStoreException extends RuntimeException {

        TokenStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
