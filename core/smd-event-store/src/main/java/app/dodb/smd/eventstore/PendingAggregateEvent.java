package app.dodb.smd.eventstore;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.eventstore.storage.AggregateRevision;

import java.util.Optional;

import static app.dodb.smd.api.utils.LoggingUtils.logClass;
import static java.util.Objects.requireNonNull;

// Carries the aggregate revision of one published event to EventStoreSink on the publishing thread.
// Matching by payload identity keeps the revision off any other message that handlers publish during the same publication.
final class PendingAggregateEvent {

    private static final ScopedValue<PendingAggregateEvent> CURRENT = ScopedValue.newInstance();

    private final Event event;
    private final AggregateRevision revision;
    private boolean claimed;

    private PendingAggregateEvent(Event event, AggregateRevision revision) {
        this.event = requireNonNull(event);
        this.revision = requireNonNull(revision);
    }

    static void publish(Event event, AggregateRevision revision, EventPublisher eventPublisher) {
        var pendingEvent = new PendingAggregateEvent(event, revision);
        ScopedValue.where(CURRENT, pendingEvent).run(() -> eventPublisher.publish(event));

        if (!pendingEvent.claimed) {
            throw new IllegalStateException("""
                Aggregate event was not stored: no event store received it. Register EventStore.inlet() as a sink of the event publisher.

                    Aggregate:
                    %s (%s)

                    Event:
                    %s
                """.formatted(revision.aggregateType(), revision.aggregateId(), logClass(event.getClass())));
        }
    }

    static Optional<AggregateRevision> claim(EventMessage<?> eventMessage) {
        if (!CURRENT.isBound() || CURRENT.get().event != eventMessage.payload()) {
            return Optional.empty();
        }

        var pendingEvent = CURRENT.get();
        var aggregateId = pendingEvent.revision.aggregateId();
        if (eventMessage.subjectId().filter(subjectId -> !subjectId.equals(aggregateId)).isPresent()) {
            throw new IllegalArgumentException("""
                Invalid aggregate event: a declared @SubjectId must resolve to the aggregate ID.
                Aggregate events need no @SubjectId; the event store records the aggregate ID.

                    Aggregate:
                    %s (%s)

                    Event:
                    %s

                    Subject:
                    %s
                """.formatted(pendingEvent.revision.aggregateType(), aggregateId, logClass(eventMessage.payload().getClass()),
                eventMessage.subjectId().orElseThrow()));
        }

        pendingEvent.claimed = true;
        return Optional.of(pendingEvent.revision);
    }
}
