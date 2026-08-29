package app.dodb.smd.ticket.drivingport.command;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.ticket.utils.StringUtils;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record ResolveTicketCommand(UUID ticketId, String resolution) implements Command<UUID> {

    public ResolveTicketCommand {
        requireNonNull(ticketId, "ResolveTicket.ticketId must not be null");
        resolution = StringUtils.requireText(resolution, "resolution", "ResolveTicket");
    }
}
