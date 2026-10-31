package app.dodb.smd.api.event;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectResolverTest {

    @Test
    void resolve_withAnnotatedRecordComponent_returnsSubject() {
        var event = new RecordEvent("subjectId");

        assertThat(SubjectResolver.resolve(event)).contains(new Subject("ticket", "subjectId"));
    }

    @Test
    void resolve_withAnnotatedMethod_returnsSubject() {
        var event = new AnnotatedMethodEvent("subjectId");

        assertThat(SubjectResolver.resolve(event)).contains(new Subject("ticket", "subjectId"));
    }

    @Test
    void resolve_withSubjectType_returnsSubjectOfThatType() {
        var event = new InvoiceEvent("42");

        assertThat(SubjectResolver.resolve(event)).contains(new Subject("invoice", "42"));
    }

    @Test
    void resolve_withoutAnnotation_returnsEmpty() {
        var event = new MissingSubjectIdEvent();

        assertThat(SubjectResolver.resolve(event)).isEmpty();
    }

    @Test
    void resolve_withMultipleAnnotations_rejectsEvent() {
        var event = new MultipleSubjectIdsEvent();

        assertThatThrownBy(() -> SubjectResolver.resolve(event))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: exactly one @SubjectId method or record component may be declared.");
    }

    @Test
    void resolve_withNonStringSubjectId_returnsStringRepresentationAsId() {
        var event = new NonStringSubjectIdEvent(123L);

        assertThat(SubjectResolver.resolve(event)).contains(new Subject("ticket", "123"));
    }

    @Test
    void resolve_withNullSubjectId_returnsEmpty() {
        var event = new NonStringSubjectIdEvent(null);

        assertThat(SubjectResolver.resolve(event)).isEmpty();
    }

    @Test
    void resolve_withOptionalSubjectId_returnsSubject() {
        var event = new OptionalSubjectIdEvent(Optional.of("subjectId"));

        assertThat(SubjectResolver.resolve(event)).contains(new Subject("ticket", "subjectId"));
    }

    @Test
    void resolve_withEmptyOptionalSubjectId_returnsEmpty() {
        var event = new OptionalSubjectIdEvent(Optional.empty());

        assertThat(SubjectResolver.resolve(event)).isEmpty();
    }

    @Test
    void resolve_withBlankSubjectId_rejectsEvent() {
        assertThatThrownBy(() -> SubjectResolver.resolve(new RecordEvent(" ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must resolve to a non-blank value.");
    }

    @Test
    void resolve_withOptionalBlankSubjectId_rejectsEvent() {
        assertThatThrownBy(() -> SubjectResolver.resolve(new OptionalSubjectIdEvent(Optional.of(" "))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must resolve to a non-blank value.");
    }

    @Test
    void resolve_withVoidAnnotatedMethod_rejectsEvent() {
        assertThatThrownBy(() -> SubjectResolver.resolve(new VoidSubjectIdEvent()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.");
    }

    @Test
    void resolve_withStaticAnnotatedMethod_rejectsEvent() {
        assertThatThrownBy(() -> SubjectResolver.resolve(new StaticSubjectIdEvent()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.");
    }

    @Test
    void resolve_withParameterizedAnnotatedMethod_rejectsEvent() {
        assertThatThrownBy(() -> SubjectResolver.resolve(new SubjectIdWithArgumentsEvent()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Invalid event: @SubjectId must annotate a non-static, zero-argument method or record component that returns a value.");
    }

    @Test
    void resolve_withPrivateAnnotatedMethod_returnsSubject() {
        var event = new PrivateAnnotatedMethodEvent("subjectId");

        assertThat(SubjectResolver.resolve(event)).contains(new Subject("ticket", "subjectId"));
    }

    private record RecordEvent(@SubjectId("ticket") String subjectId) implements Event {
    }

    private record InvoiceEvent(@SubjectId("invoice") String invoiceId) implements Event {
    }

    private record AnnotatedMethodEvent(String subjectId) implements Event {

        @SubjectId("ticket")
        public String subjectIdMethod() {
            return subjectId;
        }
    }

    private record MissingSubjectIdEvent() implements Event {
    }

    private record MultipleSubjectIdsEvent() implements Event {

        @SubjectId("ticket")
        public String firstSubjectId() {
            return "firstSubjectId";
        }

        @SubjectId("ticket")
        public String secondSubjectId() {
            return "secondSubjectId";
        }
    }

    private record NonStringSubjectIdEvent(@SubjectId("ticket") Long subjectId) implements Event {
    }

    private record OptionalSubjectIdEvent(@SubjectId("ticket") Optional<String> subjectId) implements Event {
    }

    private record VoidSubjectIdEvent() implements Event {

        @SubjectId("ticket")
        public void voidSubjectId() {
        }
    }

    private record StaticSubjectIdEvent() implements Event {

        @SubjectId("ticket")
        public static String staticSubjectId() {
            return "staticSubjectId";
        }
    }

    private record SubjectIdWithArgumentsEvent() implements Event {

        @SubjectId("ticket")
        public String subjectId(String value) {
            return value;
        }
    }

    private record PrivateAnnotatedMethodEvent(String subjectId) implements Event {

        @SubjectId("ticket")
        private String privateSubjectId() {
            return subjectId;
        }
    }
}
