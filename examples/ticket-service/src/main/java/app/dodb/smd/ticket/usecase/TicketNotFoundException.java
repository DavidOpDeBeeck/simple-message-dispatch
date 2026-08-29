package app.dodb.smd.ticket.usecase;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(UUID ticketId) {
        super("Ticket not found: ticketId=" + ticketId);
    }
}
