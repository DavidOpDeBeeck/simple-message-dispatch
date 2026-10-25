package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

import java.util.UUID;

public record TestTicketOpenedEvent(@SubjectId UUID ticketId, String title) implements Event {
}
