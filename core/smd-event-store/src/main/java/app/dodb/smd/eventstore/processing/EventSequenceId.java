package app.dodb.smd.eventstore.processing;

import app.dodb.smd.api.event.Subject;

import static java.util.Objects.requireNonNull;

public sealed interface EventSequenceId {

    record Global() implements EventSequenceId {
    }

    record OfSubject(Subject subject) implements EventSequenceId {

        public OfSubject {
            requireNonNull(subject);
        }
    }
}
