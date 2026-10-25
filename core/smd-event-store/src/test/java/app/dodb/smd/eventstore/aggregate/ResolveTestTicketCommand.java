package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;

import java.util.UUID;

public record ResolveTestTicketCommand(@TargetAggregateId UUID ticketId, String resolution) implements Command<UUID> {
}
