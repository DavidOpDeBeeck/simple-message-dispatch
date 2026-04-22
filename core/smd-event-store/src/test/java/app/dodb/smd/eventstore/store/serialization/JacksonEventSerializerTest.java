package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonEventSerializerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-04-22T10:15:30Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Test
    void defaultConstructor_usesClassNameEventTypeResolver() {
        var serializer = new JacksonEventSerializer(objectMapper);
        var eventMessage = EventMessage.from(new TestEvent("value"), metadata());

        var serialized = serializer.serialize(eventMessage);
        var deserialized = serializer.deserialize(serialized);

        assertThat(serialized.eventType()).isEqualTo(TestEvent.class.getName());
        assertThat(deserialized.messageId()).isEqualTo(eventMessage.messageId());
        assertThat(deserialized.payload()).isEqualTo(new TestEvent("value"));
        assertThat(deserialized.metadata()).isEqualTo(metadata());
    }

    @Test
    void classNameEventTypeResolver_rejectsClassThatDoesNotImplementEvent() {
        var resolver = new ClassNameEventTypeResolver();

        assertThatThrownBy(() -> resolver.eventClassFor(String.class.getName()))
            .isInstanceOf(EventTypeResolutionException.class)
            .hasMessageContaining("does not implement Event");
    }

    private Metadata metadata() {
        return new Metadata(null, TIMESTAMP, null);
    }

    record TestEvent(String value) implements Event {
    }
}
