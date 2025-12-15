package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.Command;

public record AddMoneyCommand(String accountId, double amount) implements Command<Boolean> {
}