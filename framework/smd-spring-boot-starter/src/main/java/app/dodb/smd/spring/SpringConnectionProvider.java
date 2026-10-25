package app.dodb.smd.spring;

import app.dodb.smd.api.framework.ConnectionProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.sql.Connection;
import javax.sql.DataSource;

import static java.util.Objects.requireNonNull;

public class SpringConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    public SpringConnectionProvider(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource);
    }

    @Override
    public Connection getConnection() {
        return DataSourceUtils.getConnection(dataSource);
    }

    @Override
    public void releaseConnection(Connection connection) {
        DataSourceUtils.releaseConnection(connection, dataSource);
    }
}
