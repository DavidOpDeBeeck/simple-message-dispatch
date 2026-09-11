package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryBusIntegrationTest {

    @BeforeEach
    void setUp() {
        HelloQueryHandler.handledQueries.clear();
    }

    @Test
    void send_withDiscoveredHandler_returnsResult() {
        // Given
        var queryBus = QueryBusSpec.withDefaults()
            .queryHandlers(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator()))
            .create();

        var query = new HelloQuery("World");

        // When
        var result = queryBus.send(query);

        // Then
        assertThat(result).isEqualTo("Hello World");
        assertThat(HelloQueryHandler.handledQueries).containsExactly(query);
    }

    @Test
    void send_withInterceptor_interceptsQuery() {
        // Given
        var interceptor = new QueryBusInterceptorForTest();
        var queryBus = QueryBusSpec.withDefaults()
            .queryHandlers(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator()))
            .interceptors(interceptor)
            .create();

        var query = new HelloQuery("World");

        // When
        queryBus.send(query);

        // Then
        assertThat(interceptor.getInterceptedQueries()).containsExactly(query);
    }
}
