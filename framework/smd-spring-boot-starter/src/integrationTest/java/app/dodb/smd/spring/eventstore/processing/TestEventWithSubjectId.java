package app.dodb.smd.spring.eventstore.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record TestEventWithSubjectId(@SubjectId String subjectId) implements Event {
}
