package app.dodb.smd.eventstore.durable;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPositionTest {

    @Test
    void create_blankOutbox_fails() {
        // When / Then
        assertThatThrownBy(() -> new OutboxPosition(" ", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Outbox position requires an outbox name of 1 to 255 characters; got ' '");
    }

    @Test
    void create_outboxNameTooLong_fails() {
        // Given
        var outbox = "o".repeat(256);

        // When / Then
        assertThatThrownBy(() -> new OutboxPosition(outbox, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("Outbox position requires an outbox name of 1 to 255 characters");
    }

    @Test
    void create_positionZero_fails() {
        // When / Then
        assertThatThrownBy(() -> new OutboxPosition("orders.events", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Outbox position must be at least 1; got 0 for outbox 'orders.events'");
    }
}
