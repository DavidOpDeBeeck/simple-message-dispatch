package app.dodb.smd.spring.jms.durable;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record PaymentReceivedEvent(@SubjectId String orderId, int installment) implements Event {
}
