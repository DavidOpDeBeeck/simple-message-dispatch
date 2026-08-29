package app.dodb.smd.ticket.drivingport.command;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.ticket.utils.StringUtils;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record AssignTicketCommand(UUID ticketId, String assignee) implements Command<UUID> {

    public AssignTicketCommand {
        requireNonNull(ticketId, "AssignTicket.ticketId must not be null");
        assignee = StringUtils.requireText(assignee, "assignee", "AssignTicket");
    }
}
