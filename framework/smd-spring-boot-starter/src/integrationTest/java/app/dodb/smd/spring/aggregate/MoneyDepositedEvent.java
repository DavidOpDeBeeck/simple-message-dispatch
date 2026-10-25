package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.event.Event;

import java.util.UUID;

public record MoneyDepositedEvent(UUID accountId, long amount) implements Event {
}
