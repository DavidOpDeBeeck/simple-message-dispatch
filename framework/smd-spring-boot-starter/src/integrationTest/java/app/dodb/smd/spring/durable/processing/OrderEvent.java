package app.dodb.smd.spring.durable.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record OrderEvent(@SubjectId String orderId, int sequence) implements Event {
}
