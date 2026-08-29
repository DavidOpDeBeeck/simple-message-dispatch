package app.dodb.smd.ticket.domain;

import app.dodb.smd.ticket.drivingport.event.TicketAssignedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketEvent;
import app.dodb.smd.ticket.drivingport.event.TicketOpenedEvent;
import app.dodb.smd.ticket.drivingport.event.TicketResolvedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public class Ticket {

    private final UUID ticketId;
    private final String title;
    private final String description;
    private TicketStatus status;
    private String assignee;
    private String resolution;
    private int version;

    private final List<TicketEvent> uncommittedEvents = new ArrayList<>();

    public Ticket(UUID ticketId, String title, String description) {
        this(ticketId, title, description, TicketStatus.OPEN, null, null, 0);
        uncommittedEvents.add(new TicketOpenedEvent(ticketId, title, description));
    }

    public Ticket(UUID ticketId,
                  String title,
                  String description,
                  TicketStatus status,
                  String assignee,
                  String resolution,
                  int version) {
        this.ticketId = requireNonNull(ticketId);
        this.title = requireNonNull(title);
        this.description = requireNonNull(description);
        this.status = requireNonNull(status);
        this.assignee = assignee;
        this.resolution = resolution;
        this.version = version;
    }

    public void assign(String assignee) {
        if (status != TicketStatus.OPEN) {
            throw new InvalidTicketTransitionException(ticketId, status, "assign", TicketStatus.OPEN);
        }
        this.assignee = requireNonNull(assignee);
        status = TicketStatus.ASSIGNED;
        version++;
        uncommittedEvents.add(new TicketAssignedEvent(ticketId, assignee));
    }

    public void resolve(String resolution) {
        if (status != TicketStatus.ASSIGNED) {
            throw new InvalidTicketTransitionException(ticketId, status, "resolve", TicketStatus.ASSIGNED);
        }
        this.resolution = requireNonNull(resolution);
        status = TicketStatus.RESOLVED;
        version++;
        uncommittedEvents.add(new TicketResolvedEvent(ticketId, resolution));
    }

    public UUID ticketId() {
        return ticketId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public TicketStatus status() {
        return status;
    }

    public String assignee() {
        return assignee;
    }

    public String resolution() {
        return resolution;
    }

    public int version() {
        return version;
    }

    public List<TicketEvent> consumeEvents() {
        var copy = new ArrayList<>(uncommittedEvents);
        uncommittedEvents.clear();
        return copy;
    }
}
