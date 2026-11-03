package app.dodb.smd.ticket.drivingport.command;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.eventstore.aggregate.TargetAggregateId;
import app.dodb.smd.ticket.drivingport.Require;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record ResolveTicketCommand(@TargetAggregateId UUID ticketId, String resolution) implements Command<UUID> {

    public ResolveTicketCommand {
        requireNonNull(ticketId, "ResolveTicket.ticketId must not be null");
        resolution = Require.text(resolution, "resolution", "ResolveTicket");
    }
}
