package app.dodb.smd.spring.eventstore.processing;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.SubjectId;

public record TestEventWithSubjectId(@SubjectId(TestEventWithSubjectId.SUBJECT_TYPE) String subjectId) implements Event {

    public static final String SUBJECT_TYPE = "test-subject";
}
