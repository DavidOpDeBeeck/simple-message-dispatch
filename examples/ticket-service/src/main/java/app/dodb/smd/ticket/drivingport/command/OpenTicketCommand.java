package app.dodb.smd.ticket.drivingport.command;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.ticket.drivingport.Require;

import java.util.UUID;

public record OpenTicketCommand(String title, String description) implements Command<UUID> {

    public OpenTicketCommand {
        title = Require.text(title, "title", "OpenTicket");
        description = Require.text(description, "description", "OpenTicket");
    }
}
