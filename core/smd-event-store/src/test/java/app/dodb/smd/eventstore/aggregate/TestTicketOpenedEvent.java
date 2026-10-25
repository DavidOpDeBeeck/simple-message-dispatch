package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;

import java.util.UUID;

public record TestTicketOpenedEvent(UUID ticketId, String title) implements Event {
}
