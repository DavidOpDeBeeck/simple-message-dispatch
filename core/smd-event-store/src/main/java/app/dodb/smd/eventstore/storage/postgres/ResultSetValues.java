package app.dodb.smd.eventstore.storage.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

final class ResultSetValues {

    private ResultSetValues() {
    }

    static Optional<Long> optionalLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? empty() : of(value);
    }

    static Optional<Instant> optionalTimestamp(ResultSet rs, String columnName) throws SQLException {
        var value = rs.getTimestamp(columnName);
        return rs.wasNull() ? empty() : of(value.toInstant());
    }
}
