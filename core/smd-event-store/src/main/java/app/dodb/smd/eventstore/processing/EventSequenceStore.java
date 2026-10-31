package app.dodb.smd.eventstore.processing;

import java.util.Optional;

public interface EventSequenceStore {

    Optional<EventSequence> claim(String processingGroup, EventSequenceId sequenceId);

    Optional<EventSequenceState> eventSequenceState(String processingGroup, EventSequenceId sequenceId);
}
