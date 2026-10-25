package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

import java.util.UUID;

public record BankAccountOpenedEvent(@SubjectId UUID accountId) implements Event {
}
