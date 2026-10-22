package app.dodb.smd.spring.jms.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record OrderPlacedEvent(@SubjectId String orderId, int line) implements Event {
}
