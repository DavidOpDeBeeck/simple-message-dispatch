package app.dodb.smd.api.event;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectIdResolverTest {

    @Test
    void resolve_withAnnotatedRecordComponent_returnsSubjectId() {
        var event = new RecordEvent("subjectId");

        assertThat(SubjectIdResolver.resolve(event)).contains("subjectId");
    }

    @Test
    void resolve_withAnnotatedMethod_returnsSubjectId() {
        var event = new AnnotatedMethodEvent("subjectId");

        assertThat(SubjectIdResolver.resolve(event)).contains("subjectId");
    }

    @Test
    void resolve_withoutAnnotation_returnsEmpty() {
        var event = new MissingSubjectIdEvent();

        assertThat(SubjectIdResolver.resolve(event)).isEmpty();
    }

    @Test
    void resolve_withMultipleAnnotations_rejectsEvent() {
        var event = new MultipleSubjectIdsEvent();

        assertThatThrownBy(() -> SubjectIdResolver.resolve(event))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: exactly one @SubjectId method or record component may be declared.");
    }

    @Test
    void resolve_withNonStringSubjectId_returnsStringRepresentation() {
        var event = new NonStringSubjectIdEvent(123L);

        assertThat(SubjectIdResolver.resolve(event)).contains("123");
    }

    @Test
    void resolve_withNullSubjectId_returnsEmpty() {
        var event = new NonStringSubjectIdEvent(null);

        assertThat(SubjectIdResolver.resolve(event)).isEmpty();
    }

    @Test
    void resolve_withOptionalSubjectId_returnsSubjectId() {
        var event = new OptionalSubjectIdEvent(Optional.of("subjectId"));

        assertThat(SubjectIdResolver.resolve(event)).contains("subjectId");
    }

    @Test
    void resolve_withEmptyOptionalSubjectId_returnsEmpty() {
        var event = new OptionalSubjectIdEvent(Optional.empty());

        assertThat(SubjectIdResolver.resolve(event)).isEmpty();
    }

    @Test
    void resolve_withBlankSubjectId_rejectsEvent() {
        assertThatThrownBy(() -> SubjectIdResolver.resolve(new RecordEvent(" ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must resolve to a non-blank value.");
    }

    @Test
    void resolve_withOptionalBlankSubjectId_rejectsEvent() {
        assertThatThrownBy(() -> SubjectIdResolver.resolve(new OptionalSubjectIdEvent(Optional.of(" "))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must resolve to a non-blank value.");
    }

    @Test
    void resolve_withVoidAnnotatedMethod_rejectsEvent() {
        assertThatThrownBy(() -> SubjectIdResolver.resolve(new VoidSubjectIdEvent()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.");
    }

    @Test
    void resolve_withStaticAnnotatedMethod_rejectsEvent() {
        assertThatThrownBy(() -> SubjectIdResolver.resolve(new StaticSubjectIdEvent()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.");
    }

    @Test
    void resolve_withParameterizedAnnotatedMethod_rejectsEvent() {
        assertThatThrownBy(() -> SubjectIdResolver.resolve(new SubjectIdWithArgumentsEvent()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.");
    }

    @Test
    void resolve_withPrivateAnnotatedMethod_returnsSubjectId() {
        var event = new PrivateAnnotatedMethodEvent("subjectId");

        assertThat(SubjectIdResolver.resolve(event)).contains("subjectId");
    }

    private record RecordEvent(@SubjectId String subjectId) implements Event {
    }

    private record AnnotatedMethodEvent(String subjectId) implements Event {

        @SubjectId
        public String subjectIdMethod() {
            return subjectId;
        }
    }

    private record MissingSubjectIdEvent() implements Event {
    }

    private record MultipleSubjectIdsEvent() implements Event {

        @SubjectId
        public String firstSubjectId() {
            return "firstSubjectId";
        }

        @SubjectId
        public String secondSubjectId() {
            return "secondSubjectId";
        }
    }

    private record NonStringSubjectIdEvent(@SubjectId Long subjectId) implements Event {
    }

    private record OptionalSubjectIdEvent(@SubjectId Optional<String> subjectId) implements Event {
    }

    private record VoidSubjectIdEvent() implements Event {

        @SubjectId
        public void voidSubjectId() {
        }
    }

    private record StaticSubjectIdEvent() implements Event {

        @SubjectId
        public static String staticSubjectId() {
            return "staticSubjectId";
        }
    }

    private record SubjectIdWithArgumentsEvent() implements Event {

        @SubjectId
        public String subjectId(String value) {
            return value;
        }
    }

    private record PrivateAnnotatedMethodEvent(String subjectId) implements Event {

        @SubjectId
        private String privateSubjectId() {
            return subjectId;
        }
    }
}
