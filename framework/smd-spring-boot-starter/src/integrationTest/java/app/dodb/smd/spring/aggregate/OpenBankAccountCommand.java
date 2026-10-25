package app.dodb.smd.spring.aggregate;

import app.dodb.smd.api.command.Command;

import java.util.UUID;

public record OpenBankAccountCommand(UUID accountId) implements Command<UUID> {
}
