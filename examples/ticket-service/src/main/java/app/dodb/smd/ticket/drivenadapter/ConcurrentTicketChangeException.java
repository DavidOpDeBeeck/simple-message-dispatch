package app.dodb.smd.ticket.drivenadapter;

import java.util.UUID;

public class ConcurrentTicketChangeException extends RuntimeException {

    public ConcurrentTicketChangeException(UUID ticketId, int expectedVersion) {
        super("Ticket changed concurrently: ticketId=" + ticketId + ", expectedVersion=" + expectedVersion);
    }
}
