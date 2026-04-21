package app.dodb.smd.spring.event.processing;

import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.ProcessingGroup;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.SQLException;

@ProcessingGroup
public class SideEffectFailingTestEventHandler {

    private final DataSource dataSource;

    public SideEffectFailingTestEventHandler(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventHandler
    public void on(SideEffectTestEvent event) {
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var stmt = connection.prepareStatement("INSERT INTO event_handler_side_effects (description) VALUES (?)")) {
            stmt.setString(1, "side effect before failure");
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
        throw new RuntimeException("Simulated failure after side effect");
    }
}
