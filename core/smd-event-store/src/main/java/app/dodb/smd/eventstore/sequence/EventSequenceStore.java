package app.dodb.smd.eventstore.sequence;

import java.util.Optional;

public interface EventSequenceStore {

    Optional<EventSequence> claimGlobal(String processingGroup);

    Optional<EventSequence> claimAggregate(String processingGroup, String aggregateId);

    Optional<EventSequenceState> globalEventSequenceState(String processingGroup);

    Optional<EventSequenceState> eventSequenceState(String processingGroup, String aggregateId);

}
