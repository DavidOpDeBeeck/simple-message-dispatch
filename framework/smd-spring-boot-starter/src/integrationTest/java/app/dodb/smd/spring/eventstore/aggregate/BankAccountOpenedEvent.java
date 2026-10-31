package app.dodb.smd.spring.eventstore.aggregate;

import app.dodb.smd.api.event.Event;

import java.util.UUID;

public record BankAccountOpenedEvent(UUID accountId) implements Event {
}
