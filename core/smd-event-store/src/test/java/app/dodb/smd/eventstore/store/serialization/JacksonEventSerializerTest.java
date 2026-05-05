package app.dodb.smd.eventstore.store.serialization;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.metadata.principal.SimplePrincipal;
import app.dodb.smd.eventstore.store.SerializedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static app.dodb.smd.eventstore.store.serialization.JacksonEventSerializer.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonEventSerializerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-04-22T10:15:30Z");
    private static final SimplePrincipal PRINCIPAL = new SimplePrincipal(UUID.fromString("d9aa7511-e40c-4ef0-bc50-abef81f7d00f"));
    private static final MessageId PARENT_MESSAGE_ID = new MessageId(UUID.fromString("14d68b69-d3f7-4d61-88de-175938716af0"));

    @Test
    void defaultConstructor_serializesWithClassNameEventTypeResolverButCannotDeserializePrincipalMetadata() {
        var serializer = new JacksonEventSerializer(JsonMapper.builder().build());
        var eventMessage = EventMessage.from(new TestEvent("value"), metadata());

        var serialized = serializer.serialize(eventMessage);

        assertThat(serialized).usingRecursiveComparison()
            .isEqualTo(new SerializedEvent(
                eventMessage.messageId(),
                null,
                TestEvent.class.getName(),
                json("""
                    {
                      "value": "value"
                    }
                    """),
                json("""
                    {
                      "principal": {
                        "id": "%s"
                      },
                      "timestamp": "2026-04-22T10:15:30Z",
                      "parentMessageId": {
                        "value": "%s"
                      },
                      "properties": {
                        "correlationId": "correlation-123"
                      }
                    }
                    """.formatted(PRINCIPAL.id(), PARENT_MESSAGE_ID.value())),
                TIMESTAMP
            ));
        assertThatThrownBy(() -> serializer.deserialize(serialized))
            .isInstanceOf(EventSerializationException.class)
            .hasMessageContaining("Failed to deserialize event: " + eventMessage.messageId());
    }

    @Test
    void smdJacksonModule_roundTripsPrincipalTypeMetadata() {
        var serializer = new JacksonEventSerializer(JsonMapper.builder()
            .addModule(new SMDJacksonModule())
            .build());
        var eventMessage = EventMessage.from(new TestEvent("value"), metadata());

        var serialized = serializer.serialize(eventMessage);
        var deserialized = serializer.deserialize(serialized);

        assertThat(serialized).usingRecursiveComparison()
            .isEqualTo(new SerializedEvent(
                eventMessage.messageId(),
                null,
                TestEvent.class.getName(),
                json("""
                    {
                      "value": "value"
                    }
                    """),
                json("""
                    {
                      "principal": {
                        "type": "%s",
                        "id": "%s"
                      },
                      "timestamp": "2026-04-22T10:15:30Z",
                      "parentMessageId": {
                        "value": "%s"
                      },
                      "properties": {
                        "correlationId": "correlation-123"
                      }
                    }
                    """.formatted(SimplePrincipal.class.getName(), PRINCIPAL.id(), PARENT_MESSAGE_ID.value())),
                TIMESTAMP
            ));
        assertThat(deserialized).isEqualTo(eventMessage);
    }

    @Test
    void classNameEventTypeResolver_rejectsClassThatDoesNotImplementEvent() {
        var resolver = new ClassNameEventTypeResolver();

        assertThatThrownBy(() -> resolver.eventClassFor(String.class.getName()))
            .isInstanceOf(EventTypeResolutionException.class)
            .hasMessageContaining("does not implement Event");
    }

    private Metadata metadata() {
        return new Metadata(PRINCIPAL, TIMESTAMP, PARENT_MESSAGE_ID, Map.of("correlationId", "correlation-123"));
    }

    private static byte[] json(String value) {
        return value.replaceAll("\\s+", "").getBytes(UTF_8);
    }

    record TestEvent(String value) implements Event {
    }
}
