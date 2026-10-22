package app.dodb.smd.spring.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

record OrderEvent(@SubjectId String orderId, int sequence) implements Event {
}
