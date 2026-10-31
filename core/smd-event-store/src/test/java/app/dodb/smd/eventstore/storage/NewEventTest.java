package app.dodb.smd.eventstore.storage;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.serialization.SerializedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static app.dodb.smd.api.message.MessageId.generate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-04-23T10:15:30Z");
    private static final Subject TICKET = new Subject("ticket", "ticket-1");

    @Test
    void from_subjectVersion_keepsSubjectAndVersion() {
        var event = NewEvent.from(serializedEvent(Optional.of(TICKET)), 3L);

        assertThat(event.subject()).contains(TICKET);
        assertThat(event.subjectVersion()).contains(3L);
    }

    @Test
    void from_subject_keepsSubjectWithoutVersion() {
        var event = NewEvent.from(serializedEvent(Optional.of(TICKET)));

        assertThat(event.subject()).contains(TICKET);
        assertThat(event.subjectVersion()).isEmpty();
    }

    @Test
    void from_noSubject_hasNoSubjectOrVersion() {
        var event = NewEvent.from(serializedEvent(Optional.empty()));

        assertThat(event.subject()).isEmpty();
        assertThat(event.subjectVersion()).isEmpty();
    }

    @Test
    void from_subjectVersionWithoutSubject_fails() {
        var serializedEvent = serializedEvent(Optional.empty());

        assertThatThrownBy(() -> NewEvent.from(serializedEvent, 3L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a subject version requires a subject")
            .hasMessageContaining(serializedEvent.messageId().toString());
    }

    private static SerializedEvent serializedEvent(Optional<Subject> subject) {
        return new SerializedEvent(generate(), subject, "test-event", new byte[0], new byte[0], OCCURRED_AT);
    }
}
