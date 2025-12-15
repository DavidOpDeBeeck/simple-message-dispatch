package app.dodb.smd.api.query;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotatedQueryHandlerTest {

    @Test
    void handle_withQueryParameter() {
        var registry = AnnotatedQueryHandler.from(new QueryHandlerWithQueryParameter());

        assertThat(registry.queryHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutQueryParameter() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithoutQueryParameter()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: method must include a parameter of type Query.");
    }

    @Test
    void handle_withQueryAndMetadataParameter() {
        var registry = AnnotatedQueryHandler.from(new QueryHandlerWithQueryAndMetadataParameter());

        assertThat(registry.queryHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutParameters() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithoutParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: method must have at least one parameter.");
    }

    @Test
    void handle_withMultipleQueryTypes() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithMultipleQueryTypes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: method must only include one Query as a parameter.");
    }

    @Test
    void handle_withIncorrectReturnType() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithIncorrectReturnType()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: return type mismatch.");
    }

    @Test
    void handle_withMultipleHandlersForSameQuery() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new MultipleQueryHandlersForSameQuery()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Ambiguous query handlers found");
    }

    public record QueryForTest() implements Query<String> {
    }

    public record AnotherQueryForTest() implements Query<Integer> {
    }

    public static class QueryHandlerWithQueryParameter {

        @QueryHandler
        public String handle(QueryForTest query) {
            return "";
        }
    }

    public static class QueryHandlerWithQueryAndMetadataParameter {

        @QueryHandler
        public String handle(QueryForTest query, Metadata metadata, MessageId messageId) {
            return "";
        }
    }

    public static class MultipleQueryHandlersForSameQuery {

        @QueryHandler
        public String handle(QueryForTest query) {
            return "";
        }

        @QueryHandler
        public String handle2(QueryForTest query) {
            return "";
        }
    }

    public static class QueryHandlerWithoutQueryParameter {

        @QueryHandler
        public String handle(Metadata metadata) {
            return "";
        }
    }

    public static class QueryHandlerWithoutParameters {

        @QueryHandler
        public String handle() {
            return "";
        }
    }

    public static class QueryHandlerWithMultipleQueryTypes {

        @QueryHandler
        public Integer handle(QueryForTest query, AnotherQueryForTest anotherQuery) {
            return 0;
        }
    }

    public static class QueryHandlerWithIncorrectReturnType {

        @QueryHandler
        public Integer handle(QueryForTest query) {
            return 0;
        }
    }
}