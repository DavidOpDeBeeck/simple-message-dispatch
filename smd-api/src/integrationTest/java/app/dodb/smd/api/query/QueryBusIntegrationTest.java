package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.datetime.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.principal.SimplePrincipalProvider;
import app.dodb.smd.api.query.bus.QueryBus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class QueryBusIntegrationTest {

    @Test
    void send() {
        QueryBus queryBus = new QueryBus(
            new MetadataFactory(new SimplePrincipalProvider(), new LocalDatetimeProvider()),
            new QueryHandlerDispatcher(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator())),
            emptyList()
        );

        var query = new HelloQuery("World");
        var result = queryBus.send(query);

        assertThat(result).isEqualTo("Hello World");
        assertThat(HelloQueryHandler.handledQueries).containsExactly(query);
    }

    @Test
    void send_withInterceptor() {
        var interceptor = new QueryBusInterceptorForTest();
        QueryBus queryBus = new QueryBus(
            new MetadataFactory(new SimplePrincipalProvider(), new LocalDatetimeProvider()),
            new QueryHandlerDispatcher(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator())),
            List.of(interceptor)
        );

        var query = new HelloQuery("World");
        queryBus.send(query);

        assertThat(interceptor.getInterceptedQueries()).containsExactly(query);
    }
}