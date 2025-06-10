package app.dodb.smd.api.query;

import app.dodb.smd.api.framework.ConstructorBasedObjectCreator;
import app.dodb.smd.api.metadata.LocalDatetimeProvider;
import app.dodb.smd.api.metadata.MetadataFactory;
import app.dodb.smd.api.metadata.PrincipalProviderImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryBusIntegrationTest {

    @Test
    void send() {
        QueryBus queryBus = new QueryBus(
            new MetadataFactory(new PrincipalProviderImpl(), new LocalDatetimeProvider()),
            new QueryHandlerDispatcher(new PackageBasedQueryHandlerLocator(List.of("app.dodb.smd.api.query"), new ConstructorBasedObjectCreator()))
        );

        var query = new HelloQuery("World");
        var result = queryBus.send(query);

        assertThat(result).isEqualTo("Hello World");
        assertThat(HelloQueryHandler.handledQueries).containsExactly(query);
    }
}