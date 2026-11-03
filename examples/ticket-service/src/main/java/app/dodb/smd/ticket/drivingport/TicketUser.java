package app.dodb.smd.ticket.drivingport;

import app.dodb.smd.api.metadata.principal.Principal;

import static java.util.Objects.requireNonNull;

public record TicketUser(String name) implements Principal {

    public static final TicketUser ANONYMOUS = new TicketUser("anonymous");

    public TicketUser {
        requireNonNull(name);
    }
}
