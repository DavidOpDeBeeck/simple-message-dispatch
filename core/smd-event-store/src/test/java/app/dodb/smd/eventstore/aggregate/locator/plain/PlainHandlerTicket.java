package app.dodb.smd.eventstore.aggregate.locator.plain;

import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.eventstore.aggregate.Aggregate;
import app.dodb.smd.eventstore.aggregate.AggregateId;
import app.dodb.smd.eventstore.aggregate.AssignTestTicketCommand;

import java.util.UUID;

@Aggregate("plain-handler-ticket")
public class PlainHandlerTicket {

    @AggregateId
    private UUID id;

    private PlainHandlerTicket() {
    }

    @CommandHandler
    public String handle(AssignTestTicketCommand command) {
        return command.assignee();
    }
}
