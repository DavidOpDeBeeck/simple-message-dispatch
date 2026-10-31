package app.dodb.smd.spring.durabledelivery.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record OrderEvent(@SubjectId("order") String orderId, int sequence) implements Event {
}
