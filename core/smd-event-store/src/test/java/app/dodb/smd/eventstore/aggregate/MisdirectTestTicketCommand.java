package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;

import java.util.UUID;

public record MisdirectTestTicketCommand(@TargetAggregateId UUID ticketId, UUID otherTicketId) implements Command<UUID> {
}
