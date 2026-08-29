package app.dodb.smd.ticket.test;

import java.time.Instant;

public final class TicketTestConstants {

    public static final String CUSTOMER_ID = "customer-42";
    public static final String TICKET_TITLE = "Cannot sign in";
    public static final String TICKET_DESCRIPTION = "Password reset does not arrive";
    public static final String TICKET_ASSIGNEE = "agent-7";
    public static final String TICKET_RESOLUTION = "Reset mail was unblocked";
    public static final Instant TIMESTAMP = Instant.parse("2026-08-29T10:15:30Z");

    private TicketTestConstants() {
    }
}
