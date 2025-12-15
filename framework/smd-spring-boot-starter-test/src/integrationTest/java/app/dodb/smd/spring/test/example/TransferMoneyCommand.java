package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.Command;

public record TransferMoneyCommand(String fromAccount, String toAccount, double amount) implements Command<Boolean> {
}
