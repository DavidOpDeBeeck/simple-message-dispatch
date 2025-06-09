package app.dodb.smd.api.query;

import org.junit.jupiter.api.Test;

import static app.dodb.smd.api.metadata.MetadataTestConstants.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryHandlerDispatcherTest {

    @Test
    void dispatch() {
        var dispatcher = new QueryHandlerDispatcher(() -> AnnotatedQueryHandler.from(new QueryHandlerForTest()));

        var result = dispatcher.dispatch(QueryMessage.from(new QueryForTest("Hello world"), METADATA));

        assertThat(result).isEqualTo("Hello world");
    }

    @Test
    void dispatch_whenQueryHandlerThrowsException_thenRethrow() {
        var dispatcher = new QueryHandlerDispatcher(() -> AnnotatedQueryHandler.from(new QueryHandlerThatThrowsException()));
        var queryMessage = QueryMessage.from(new QueryForTest("Hello world"), METADATA);

        assertThatThrownBy(() -> dispatcher.dispatch(queryMessage))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("this is an exception");
    }

    public record QueryForTest(String value) implements Query<String> {
    }

    public static class QueryHandlerForTest {

        @QueryHandler
        public String handle(QueryForTest query) {
            return query.value();
        }
    }

    public static class QueryHandlerThatThrowsException {

        @QueryHandler
        public String handle(QueryForTest query) {
            throw new RuntimeException("this is an exception");
        }
    }
}