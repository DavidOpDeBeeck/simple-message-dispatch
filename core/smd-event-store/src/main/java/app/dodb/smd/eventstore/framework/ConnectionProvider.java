package app.dodb.smd.eventstore.framework;

import java.sql.Connection;
import java.util.function.Consumer;
import java.util.function.Function;

public interface ConnectionProvider {

    Connection getConnection();

    void releaseConnection(Connection connection);

    default void doWithConnection(Consumer<Connection> consumer) {
        var connection = getConnection();
        try {
            consumer.accept(connection);
        } finally {
            releaseConnection(connection);
        }
    }

    default <T> T doWithConnection(Function<Connection, T> function) {
        var connection = getConnection();
        try {
            return function.apply(connection);
        } finally {
            releaseConnection(connection);
        }
    }
}
