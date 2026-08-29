package app.dodb.smd.ticket.drivingport.command;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.ticket.utils.StringUtils;

import java.util.UUID;

public record OpenTicketCommand(String title, String description) implements Command<UUID> {

    public OpenTicketCommand {
        title = StringUtils.requireText(title, "title", "OpenTicket");
        description = StringUtils.requireText(description, "description", "OpenTicket");
    }
}
