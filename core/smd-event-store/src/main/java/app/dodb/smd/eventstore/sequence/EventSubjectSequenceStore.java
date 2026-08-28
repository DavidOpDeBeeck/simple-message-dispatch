package app.dodb.smd.eventstore.sequence;

import java.util.Optional;

public interface EventSubjectSequenceStore {

    Optional<EventSubjectSequence> claimGlobal(String processingGroup);

    Optional<EventSubjectSequence> claimSubject(String processingGroup, String subjectId);

    Optional<EventSequenceState> globalEventSequenceState(String processingGroup);

    Optional<EventSequenceState> eventSequenceState(String processingGroup, String subjectId);

}
