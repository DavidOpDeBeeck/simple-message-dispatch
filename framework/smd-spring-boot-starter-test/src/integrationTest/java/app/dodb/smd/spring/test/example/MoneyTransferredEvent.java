package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.event.Event;

public record MoneyTransferredEvent(String fromAccount, String toAccount, double amount) implements Event {
}