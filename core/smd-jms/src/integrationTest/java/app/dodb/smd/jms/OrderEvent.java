package app.dodb.smd.jms;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.SubjectId;
import app.dodb.smd.api.metadata.Metadata;

import java.time.Instant;
import java.util.Map;

record OrderEvent(@SubjectId String orderId, int sequence) implements Event {

    static EventMessage<OrderEvent> orderEvent(String orderId, int sequence) {
        return orderEvent(orderId, sequence, Map.of());
    }

    static EventMessage<OrderEvent> orderEvent(String orderId, int sequence, Map<String, String> properties) {
        return EventMessage.from(new OrderEvent(orderId, sequence), new Metadata(null, Instant.parse("2026-10-22T10:15:30Z"), null, properties));
    }
}
