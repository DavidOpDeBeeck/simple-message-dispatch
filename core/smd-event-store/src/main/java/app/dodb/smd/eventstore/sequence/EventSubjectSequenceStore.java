package app.dodb.smd.eventstore.sequence;

import app.dodb.smd.eventstore.storage.EventLog;

import java.util.Optional;

public interface EventSubjectSequenceStore {

    Optional<EventSubjectSequence> claimGlobal(EventLog log, String processingGroup);

    Optional<EventSubjectSequence> claimSubject(EventLog log, String processingGroup, String subjectId);

    Optional<EventSequenceState> globalEventSequenceState(EventLog log, String processingGroup);

    Optional<EventSequenceState> eventSequenceState(EventLog log, String processingGroup, String subjectId);

    default Optional<EventSequenceState> globalEventSequenceState(String processingGroup) {
        return globalEventSequenceState(EventLog.EVENTS, processingGroup);
    }

    default Optional<EventSequenceState> eventSequenceState(String processingGroup, String subjectId) {
        return eventSequenceState(EventLog.EVENTS, processingGroup, subjectId);
    }
}
