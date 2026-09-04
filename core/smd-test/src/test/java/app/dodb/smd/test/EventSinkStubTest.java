package app.dodb.smd.test;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventSinkStubTest {

    private static final Metadata METADATA = Metadata.withProperties(Map.of());

    @Test
    void send_withEventMessages_thenCapturesMessagesInOrder() {
        var first = EventMessage.from(new EventForTest("first"), METADATA);
        var second = EventMessage.from(new EventForTest("second"), METADATA);
        var sink = new EventSinkStub();

        sink.send(first);
        sink.send(second);

        assertThat(sink.getEventMessages()).containsExactly(first, second);
    }

    @Test
    void reset_withCapturedMessages_thenClearsMessages() {
        var sink = new EventSinkStub();
        sink.send(EventMessage.from(new EventForTest("event"), METADATA));

        sink.reset();

        assertThat(sink.getEventMessages()).isEmpty();
    }

    private record EventForTest(String value) implements Event {
    }
}
