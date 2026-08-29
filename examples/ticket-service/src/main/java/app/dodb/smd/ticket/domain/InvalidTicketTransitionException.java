package app.dodb.smd.ticket.domain;

import java.util.UUID;

public class InvalidTicketTransitionException extends RuntimeException {

    public InvalidTicketTransitionException(UUID ticketId,
                                            TicketStatus actual,
                                            String operation,
                                            TicketStatus expected) {
        super("Cannot " + operation + " ticket: ticketId=" + ticketId
            + ", expectedStatus=" + expected + ", actualStatus=" + actual);
    }
}
