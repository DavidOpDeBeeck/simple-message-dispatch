package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.query.bus.QueryBusSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryBusIntegrationTest {

    @Test
    void send() {
        var queryBus = QueryBusSpec.withDefaults()
            .queryHandlers(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator()))
            .create();

        var query = new HelloQuery("World");
        var result = queryBus.send(query);

        assertThat(result).isEqualTo("Hello World");
        assertThat(HelloQueryHandler.handledQueries).containsExactly(query);
    }

    @Test
    void send_withInterceptor() {
        var interceptor = new QueryBusInterceptorForTest();
        var queryBus = QueryBusSpec.withDefaults()
            .queryHandlers(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator()))
            .interceptors(interceptor)
            .create();

        var query = new HelloQuery("World");
        queryBus.send(query);

        assertThat(interceptor.getInterceptedQueries()).containsExactly(query);
    }
}