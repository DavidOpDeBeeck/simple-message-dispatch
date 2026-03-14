package app.dodb.smd.eventstore.store;

import app.dodb.smd.eventstore.framework.ConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public Token getToken(String processingGroup) {
        return new PostgresToken(processingGroup, connectionProvider);
    }

    static class PostgresToken implements Token {

        private static final String SELECT_TOKEN = """
            SELECT last_processed_message_id, last_processed_sequence_number, last_failed_message_id,
                   error_count, last_error_at, last_gap_detected_at, gap_sequence_number
            FROM smd_token_store
            WHERE processing_group = ?
            """;

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

        PostgresToken(String processingGroup, ConnectionProvider connectionProvider) {
            this.processingGroup = requireNonNull(processingGroup);
            this.connectionProvider = requireNonNull(connectionProvider);
        }

        @Override
        public Optional<Long> lastProcessedSequenceNumber() {
            return connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(SELECT_TOKEN)) {
                    stmt.setString(1, processingGroup);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            long sequenceNumber = rs.getLong("last_processed_sequence_number");
                            if (rs.wasNull()) {
                                return empty();
                            }
                            return of(sequenceNumber);
                        }
                        return empty();
                    }
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to read token for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public int errorCount() {
            return connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(SELECT_TOKEN)) {
                    stmt.setString(1, processingGroup);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt("error_count");
                        }
                        return 0;
                    }
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to read error count for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public Instant lastErrorAt() {
            return connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(SELECT_TOKEN)) {
                    stmt.setString(1, processingGroup);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            Timestamp timestamp = rs.getTimestamp("last_error_at");
                            if (timestamp != null) {
                                return timestamp.toInstant();
                            }
                        }
                        return null;
                    }
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to read last error timestamp for processing group: " + processingGroup, e);
                }
            });
        }

        @Override
        public Instant lastGapDetectedAt() {
            return connectionProvider.doWithConnection(connection -> {
                try (PreparedStatement stmt = connection.prepareStatement(SELECT_TOKEN)) {
                    stmt.setString(1, processingGroup);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            Timestamp timestamp = rs.getTimestamp("last_gap_detected_at");
                            if (timestamp != null) {
                                return timestamp.toInstant();
                            }
                        }
                        return null;
                    }
                } catch (SQLException e) {
                    throw new TokenStoreException("Failed to read last gap detected timestamp for processing group: " + processingGroup, e);
                }
            });
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
