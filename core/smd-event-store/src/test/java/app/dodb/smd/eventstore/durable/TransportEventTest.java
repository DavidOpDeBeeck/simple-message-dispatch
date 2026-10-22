package app.dodb.smd.eventstore.durable;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.eventstore.storage.SerializedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportEventTest {

    private static final MessageId MESSAGE_ID = new MessageId(UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2"));

    @Test
    void create_subjectWithPosition_keepsBoth() {
        // Given
        var event = serializedEvent(Optional.of("order-1"));
        var position = Optional.of(new OutboxPosition("orders.events", 3));

        // When
        var transportEvent = new TransportEvent(event, position);

        // Then
        assertThat(transportEvent.event()).isSameAs(event);
        assertThat(transportEvent.position()).isEqualTo(position);
    }

    @Test
    void create_withoutSubjectAndPosition_isUnordered() {
        // Given
        var event = serializedEvent(Optional.empty());

        // When
        var transportEvent = new TransportEvent(event, Optional.empty());

        // Then
        assertThat(transportEvent.position()).isEmpty();
    }

    @Test
    void create_subjectWithoutPosition_fails() {
        // Given
        var event = serializedEvent(Optional.of("order-1"));

        // When / Then
        assertThatThrownBy(() -> new TransportEvent(event, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A transported event carries an outbox position exactly when it has a subject: messageId=" + MESSAGE_ID.value()
                + ", subjectId=order-1, position=null");
    }

    @Test
    void create_positionWithoutSubject_fails() {
        // Given
        var event = serializedEvent(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> new TransportEvent(event, Optional.of(new OutboxPosition("orders.events", 1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subjectId=null");
    }

    private static SerializedEvent serializedEvent(Optional<String> subjectId) {
        return new SerializedEvent(MESSAGE_ID, null, subjectId, "order.v1", new byte[0], new byte[0], Instant.parse("2026-10-22T10:15:30Z"));
    }
}
