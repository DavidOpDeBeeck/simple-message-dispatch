package app.dodb.smd.eventstore.utils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

public class SqlUtils {

    public static Optional<Long> optionalLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? empty() : of(value);
    }

    public static Optional<Instant> optionalTimestamp(ResultSet rs, String columnName) throws SQLException {
        var value = rs.getTimestamp(columnName);
        return rs.wasNull() ? empty() : of(value.toInstant());
    }
}
