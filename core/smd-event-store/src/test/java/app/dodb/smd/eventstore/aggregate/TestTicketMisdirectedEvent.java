package app.dodb.smd.eventstore.aggregate;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

import java.util.UUID;

public record TestTicketMisdirectedEvent(@SubjectId UUID otherTicketId) implements Event {
}
