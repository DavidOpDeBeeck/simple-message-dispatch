package app.dodb.smd.spring.eventstore.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record SideEffectTestEventWithSubjectId(@SubjectId(TestEventWithSubjectId.SUBJECT_TYPE) String subjectId) implements Event {
}
