package app.dodb.smd.api.query;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.MetadataValue;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
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
            .hasMessageStartingWith("Invalid handler: method must include a parameter of type %s.".formatted(logClass(Query.class)));
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
    void handle_withQueryAndMultipleMetadataValueParameters() {
        var registry = AnnotatedQueryHandler.from(new QueryHandlerWithQueryAndMultipleMetadataValueParameters());

        assertThat(registry.queryHandlers()).hasSize(1);
    }

    @Test
    void handle_withoutMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithoutMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: metadata value parameter must be annotated with @MetadataValue.");
    }

    @Test
    void handle_withIncorrectMetadataValueAnnotation() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithIncorrectMetadataValueAnnotation()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: only parameters of type String can be annotated with @MetadataValue.");
    }

    @Test
    void handle_withDuplicateMessageIdParameters() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithDuplicateMessageIdParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(MessageId.class)));
    }

    @Test
    void handle_withDuplicateMetadataParameters() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithDuplicateMetadataParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(Metadata.class)));
    }

    @Test
    void handle_withDuplicatePrincipalParameters() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithDuplicatePrincipalParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(app.dodb.smd.api.metadata.principal.Principal.class)));
    }

    @Test
    void handle_withDuplicateInstantParameters() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithDuplicateInstantParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(Instant.class)));
    }

    @Test
    void handle_withoutParameters() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithoutParameters()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must have at least one parameter.");
    }

    @Test
    void handle_withMultipleQueryTypes() {
        assertThatThrownBy(() -> AnnotatedQueryHandler.from(new QueryHandlerWithMultipleQueryTypes()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid handler: method must only include one parameter of type %s.".formatted(logClass(Query.class)));
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

    public static class QueryHandlerWithQueryAndMultipleMetadataValueParameters {

        @QueryHandler
        public String handle(QueryForTest query, @MetadataValue("value1") String value1, @MetadataValue("value2") String value2) {
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

    public static class QueryHandlerWithDuplicateMessageIdParameters {

        @QueryHandler
        public String handle(QueryForTest query, MessageId messageId1, MessageId messageId2) {
            return "";
        }
    }

    public static class QueryHandlerWithDuplicateMetadataParameters {

        @QueryHandler
        public String handle(QueryForTest query, Metadata metadata1, Metadata metadata2) {
            return "";
        }
    }

    public static class QueryHandlerWithDuplicatePrincipalParameters {

        @QueryHandler
        public String handle(QueryForTest query, SimplePrincipal principal1, SimplePrincipal principal2) {
            return "";
        }
    }

    public static class QueryHandlerWithDuplicateInstantParameters {

        @QueryHandler
        public String handle(QueryForTest query, Instant instant1, Instant instant2) {
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
