package app.dodb.smd.api.query;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotatedQueryHandlerTest {

    @Test
    void handle_withQueryParameter() {
        var registry = AnnotatedQueryHandler.from(new QueryHandlerWithQueryParameter());

        assertThat(registry.queryHandlers()).hasSize(1);
    }

    @Test
    void handle_withGenericQueryParameter() {
        var registry = AnnotatedQueryHandler.from(new QueryHandlerWithGenericQueryParameter());

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
    void handle_withQueryAndMetadataValueParameter() {
        var registry = AnnotatedQueryHandler.from(new QueryHandlerWithQueryAndMetadataValueParameter());

        assertThat(registry.queryHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithoutMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: metadata value parameter must be annotated with @MetadataValue.");
    }

    @Test
    void handle_withIncorrectMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithIncorrectMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: only parameters of type String can be annotated with @MetadataValue.");
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

    @Test
    void handle_withNonPublicAnnotatedMethod() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new NonPublicAnnotatedQueryHandler()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid query handler: method must be public.");
    }

    public record QueryForTest() implements Query<String> {
    }

    public record GenericQueryForTest<T>() implements Query<T> {
    }

    public record AnotherQueryForTest() implements Query<Integer> {
    }

    public static class QueryHandlerWithQueryParameter {

        @QueryHandler
        public String handle(QueryForTest query) {
            return "";
        }
    }

    public static class QueryHandlerWithGenericQueryParameter {

        @QueryHandler
        public <T> T handle(GenericQueryForTest<T> query) {
            return null;
        }
    }

    public static class QueryHandlerWithQueryAndMetadataParameter {

        @QueryHandler
        public String handle(QueryForTest query, Metadata metadata, MessageId messageId, SimplePrincipal principal, Instant timestamp) {
            return "";
        }
    }

    public static class QueryHandlerWithQueryAndMetadataValueParameter {

        @QueryHandler
        public String handle(QueryForTest query, @MetadataValue("value") String value) {
            return "";
        }
    }

    public static class QueryHandlerWithoutMetadataValueAnnotation {

        @QueryHandler
        public String handle(QueryForTest query, String value) {
            return "";
        }
    }

    public static class QueryHandlerWithIncorrectMetadataValueAnnotation {

        @QueryHandler
        public String handle(QueryForTest query, @MetadataValue("property") Metadata metadata) {
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

    public static class NonPublicAnnotatedQueryHandler {

        @QueryHandler
        String handle(QueryForTest query) {
            return "";
        }
    }
}
