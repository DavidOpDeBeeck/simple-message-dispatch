package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.Command;

public record ReserveFundsCommand(String account, double amount) implements Command<Void> {
}
