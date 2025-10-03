package app.dodb.smd.spring.metadata.example;

import app.dodb.smd.api.command.Command;

import java.util.UUID;

public record CreateAccountCommand(String name) implements Command<UUID> {
}
