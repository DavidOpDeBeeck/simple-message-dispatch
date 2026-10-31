package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.command.Command;

import java.util.UUID;

public record AssignTestTicketCommand(@TargetAggregateId UUID ticketId, String assignee) implements Command<String> {
}
