package app.dodb.smd.ticket.drivingport.event;

import app.dodb.smd.api.event.Event;

import java.util.UUID;

public sealed interface TicketEvent extends Event
    permits TicketOpenedEvent, TicketAssignedEvent, TicketResolvedEvent {

    UUID ticketId();
}
