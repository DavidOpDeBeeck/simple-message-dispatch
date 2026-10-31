package app.dodb.smd.eventstore.processing;

import static java.util.Objects.requireNonNull;

sealed interface EventSequenceProcessingResult {

    boolean advancesToken();

    record Processed() implements EventSequenceProcessingResult {

        @Override
        public boolean advancesToken() {
            return true;
        }
    }

    record AlreadyProcessed() implements EventSequenceProcessingResult {

        @Override
        public boolean advancesToken() {
            return true;
        }
    }

    record Abandoned() implements EventSequenceProcessingResult {

        @Override
        public boolean advancesToken() {
            return true;
        }
    }

    record BackoffActive() implements EventSequenceProcessingResult {

        @Override
        public boolean advancesToken() {
            return false;
        }
    }

    record SequenceClaimed() implements EventSequenceProcessingResult {

        @Override
        public boolean advancesToken() {
            return false;
        }
    }

    record Failure(long globalPosition, EventSequenceId sequenceId, Exception exception) implements EventSequenceProcessingResult {

        public Failure {
            requireNonNull(sequenceId);
            requireNonNull(exception);
        }

        @Override
        public boolean advancesToken() {
            return false;
        }
    }
}
