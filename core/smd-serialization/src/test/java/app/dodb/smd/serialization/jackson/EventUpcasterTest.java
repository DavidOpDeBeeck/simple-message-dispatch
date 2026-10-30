package app.dodb.smd.serialization.jackson;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.serialization.SerializedEvent;
import app.dodb.smd.serialization.StaticEventTypeResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.dodb.smd.serialization.jackson.JacksonEventSerializer.EventSerializationException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUpcasterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().addModule(new SMDJacksonModule()).build();
    private static final Metadata METADATA = Metadata.builder().timestamp(Instant.parse("2026-04-22T10:15:30Z")).property("correlationId", "correlation-123").build();
    private static final StaticEventTypeResolver RESOLVER = new StaticEventTypeResolver(Map.of(
        "ticket.opened.v3", TicketOpenedEvent.class,
        "ticket.assigned.v2", TicketAssignedEvent.class
    ));

    @Test
    void constructor_duplicateUpcastersForEventType_throwsException() {
        var upcasters = List.of(
            EventUpcaster.rename("ticket.opened.v2", "ticket.opened.v3"),
            EventUpcaster.rename("ticket.opened.v2", "ticket.assigned.v2")
        );

        assertThatThrownBy(() -> new JacksonEventSerializer(MAPPER, RESOLVER, upcasters))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Duplicate event upcasters for event type 'ticket.opened.v2': EventUpcaster['ticket.opened.v2' -> 'ticket.opened.v3'], "
                + "EventUpcaster['ticket.opened.v2' -> 'ticket.assigned.v2']; register one upcaster per event type");
    }

    @Test
    void constructor_upcasterCycle_throwsExceptionWithPath() {
        var upcasters = List.of(
            EventUpcaster.rename("ticket.opened.v1", "ticket.opened.v2"),
            EventUpcaster.rename("ticket.opened.v2", "ticket.created.v1"),
            EventUpcaster.rename("ticket.created.v1", "ticket.opened.v1")
        );

        assertThatThrownBy(() -> new JacksonEventSerializer(MAPPER, RESOLVER, upcasters))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Event upcasters form a cycle: ticket.opened.v1 -> ticket.opened.v2 -> ticket.created.v1 -> ticket.opened.v1");
    }

    @Test
    void constructor_upcasterToItself_throwsExceptionWithPath() {
        var upcasters = List.of(EventUpcaster.rename("ticket.opened.v2", "ticket.opened.v2"));

        assertThatThrownBy(() -> new JacksonEventSerializer(MAPPER, RESOLVER, upcasters))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Event upcasters form a cycle: ticket.opened.v2 -> ticket.opened.v2");
    }

    @Test
    void constructor_upcastedEventTypeWithoutUpcasterOrClass_throwsException() {
        var upcasters = List.of(
            EventUpcaster.rename("ticket.opened.v1", "ticket.opened.v2"),
            EventUpcaster.rename("ticket.created.v1", "ticket.opened.v1")
        );

        assertThatThrownBy(() -> new JacksonEventSerializer(MAPPER, RESOLVER, upcasters))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("EventUpcaster['ticket.opened.v1' -> 'ticket.opened.v2'] produces event type 'ticket.opened.v2', "
                + "which has no upcaster and can not be resolved");
    }

    @Test
    void constructor_upcastedEventTypeAlsoResolvedToClass_throwsException() {
        var upcasters = List.of(EventUpcaster.rename("ticket.opened.v3", "ticket.assigned.v2"));

        assertThatThrownBy(() -> new JacksonEventSerializer(MAPPER, RESOLVER, upcasters))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Event type 'ticket.opened.v3' is upcast by EventUpcaster['ticket.opened.v3' -> 'ticket.assigned.v2'] but can also be resolved to "
                + TicketOpenedEvent.class.getName() + "; map only the current event type name");
    }

    @Test
    void deserialize_currentEventType_readsEventWithoutUpcasting() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.transform("ticket.opened.v2", "ticket.opened.v3", _ -> {
                throw new AssertionError("Current event type must not be upcast");
            })
        ));
        var stored = stored("ticket.opened.v3", """
            {"ticketId": "ticket-1", "title": "Cannot sign in", "priority": "HIGH"}
            """);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized.payload()).isEqualTo(new TicketOpenedEvent("ticket-1", "Cannot sign in", "HIGH"));
    }

    @Test
    void deserialize_renamedEventType_readsCurrentEvent() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.rename("ticket.created.v3", "ticket.opened.v3")
        ));
        var stored = stored("ticket.created.v3", """
            {"ticketId": "ticket-1", "title": "Cannot sign in", "priority": "HIGH"}
            """);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized.payload()).isEqualTo(new TicketOpenedEvent("ticket-1", "Cannot sign in", "HIGH"));
    }

    @Test
    void deserialize_addedFieldWithDefault_readsCurrentEvent() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.transform("ticket.opened.v2", "ticket.opened.v3", payload -> payload.put("priority", "NORMAL"))
        ));
        var stored = stored("ticket.opened.v2", """
            {"ticketId": "ticket-1", "title": "Cannot sign in"}
            """);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized.payload()).isEqualTo(new TicketOpenedEvent("ticket-1", "Cannot sign in", "NORMAL"));
    }

    @Test
    void deserialize_renamedField_readsCurrentEvent() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.transform("ticket.assigned.v1", "ticket.assigned.v2", payload -> {
                payload.set("assignee", payload.remove("agent"));
                return payload;
            })
        ));
        var stored = stored("ticket.assigned.v1", """
            {"ticketId": "ticket-1", "agent": "alice"}
            """);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized.payload()).isEqualTo(new TicketAssignedEvent("ticket-1", "alice"));
    }

    @Test
    void deserialize_multiStepChain_appliesUpcastersInTypeNameOrderRegardlessOfRegistrationOrder() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.transform("ticket.opened.v2", "ticket.opened.v3", payload -> payload.put("priority", "NORMAL")),
            EventUpcaster.transform("ticket.opened.v1", "ticket.opened.v2", payload -> payload.put("title", payload.get("title").asString().trim())),
            EventUpcaster.rename("ticket.created.v1", "ticket.opened.v1")
        ));
        var stored = stored("ticket.created.v1", """
            {"ticketId": "ticket-1", "title": "  Cannot sign in  "}
            """);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized.payload()).isEqualTo(new TicketOpenedEvent("ticket-1", "Cannot sign in", "NORMAL"));
    }

    @Test
    void deserialize_upcastedEvent_keepsStoredMessageIdSubjectAndMetadata() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.transform("ticket.opened.v2", "ticket.opened.v3", payload -> payload.put("priority", "NORMAL"))
        ));
        var stored = stored("ticket.opened.v2", """
            {"ticketId": "ticket-1", "title": "Cannot sign in"}
            """);

        var deserialized = serializer.deserialize(stored);

        assertThat(deserialized).isEqualTo(new EventMessage<>(
            stored.messageId(),
            Optional.of("stored-subject"),
            new TicketOpenedEvent("ticket-1", "Cannot sign in", "NORMAL"),
            METADATA
        ));
    }

    @Test
    void deserialize_transformationReturnsNull_throwsExceptionWithContext() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.rename("ticket.opened.v1", "ticket.opened.v2"),
            EventUpcaster.transform("ticket.opened.v2", "ticket.opened.v3", _ -> null)
        ));
        var stored = stored("ticket.opened.v1", """
            {"ticketId": "ticket-1", "title": "Cannot sign in"}
            """);

        assertThatThrownBy(() -> serializer.deserialize(stored))
            .isInstanceOf(EventSerializationException.class)
            .hasMessage("EventUpcaster['ticket.opened.v2' -> 'ticket.opened.v3'] returned null for event %s stored as 'ticket.opened.v1'"
                .formatted(stored.messageId()));
    }

    @Test
    void deserialize_upcastedPayloadIsNotJsonObject_throwsExceptionWithContext() {
        var serializer = new JacksonEventSerializer(MAPPER, RESOLVER, List.of(
            EventUpcaster.rename("ticket.opened.v2", "ticket.opened.v3")
        ));
        var stored = stored("ticket.opened.v2", """
            ["ticket-1"]
            """);

        assertThatThrownBy(() -> serializer.deserialize(stored))
            .isInstanceOf(EventSerializationException.class)
            .hasMessage("Cannot upcast event %s stored as 'ticket.opened.v2': payload is not a JSON object".formatted(stored.messageId()));
    }

    private static SerializedEvent stored(String eventType, String payload) {
        return new SerializedEvent(
            MessageId.generate(),
            1L,
            Optional.of("stored-subject"),
            eventType,
            payload.getBytes(UTF_8),
            MAPPER.writeValueAsBytes(JacksonEventSerializer.MetadataJson.from(METADATA)),
            METADATA.timestamp()
        );
    }

    record TicketOpenedEvent(@SubjectId String ticketId, String title, String priority) implements Event {
    }

    record TicketAssignedEvent(@SubjectId String ticketId, String assignee) implements Event {
    }
}
