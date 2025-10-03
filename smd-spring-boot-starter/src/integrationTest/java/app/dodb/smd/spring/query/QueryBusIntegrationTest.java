package app.dodb.smd.spring.query;

import app.dodb.smd.api.query.bus.QueryBus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.WebApplicationType.NONE;

class QueryBusIntegrationTest {

    @ParameterizedTest(name = "send with {0}")
    @ValueSource(classes = {
        QueryIntegrationTestConfigurationWithDefaults.class,
        QueryIntegrationTestConfigurationWithoutDefaults.class
    })
    void send(Class<?> configClass) {
        try (var context = new SpringApplicationBuilder(configClass).web(NONE).run()) {
            var queryBus = context.getBean(QueryBus.class);
            var helloQueryHandler = context.getBean(HelloQueryHandler.class);

            var query = new HelloQuery("World");

            var result = queryBus.send(query);

            assertThat(result).isEqualTo("Hello World");
            assertThat(helloQueryHandler.getHandledQueries()).containsExactly(query);
        }
    }
}