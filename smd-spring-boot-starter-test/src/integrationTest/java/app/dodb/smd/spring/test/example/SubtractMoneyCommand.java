package app.dodb.smd.spring.test.example;

import app.dodb.smd.api.command.Command;

public record SubtractMoneyCommand(String accountId, double amount) implements Command<Boolean> {
}