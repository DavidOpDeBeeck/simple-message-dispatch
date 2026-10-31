package app.dodb.smd.spring.eventstore.aggregate;

import app.dodb.smd.api.command.Command;

import java.util.UUID;

public record OpenAuditedAccountCommand(UUID accountId) implements Command<UUID> {
}
