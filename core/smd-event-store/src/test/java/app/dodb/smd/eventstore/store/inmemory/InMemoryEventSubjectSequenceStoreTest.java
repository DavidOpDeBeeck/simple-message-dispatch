package app.dodb.smd.eventstore.store.inmemory;

import org.junit.jupiter.api.Test;

import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventSubjectSequenceStoreTest {

    @Test
    void eventSequenceState_whenSequenceDoesNotExist_returnsEmpty() {
        var store = new InMemoryEventSubjectSequenceStore();

        assertThat(store.globalEventSequenceState("processing-group")).isEmpty();
        assertThat(store.eventSequenceState("processing-group", "subjectId")).isEmpty();
    }

    @Test
    void claimGlobal_preservesStatePerProcessingGroup() {
        var store = new InMemoryEventSubjectSequenceStore();
        store.claimGlobal("first-group").orElseThrow().markProcessed(3L);

        assertThat(store.claimGlobal("first-group").orElseThrow()
            .lastProcessedSequenceNumber()).contains(3L);
        assertThat(store.claimGlobal("second-group").orElseThrow()
            .lastProcessedSequenceNumber()).isEmpty();
    }

    @Test
    void claimGlobal_doesNotAliasEmptySubject() {
        var store = new InMemoryEventSubjectSequenceStore();
        store.claimGlobal("processing-group").orElseThrow().markProcessed(3L);
        store.claimSubject("processing-group", "").orElseThrow().markProcessed(4L);

        assertThat(store.claimGlobal("processing-group").orElseThrow()
            .lastProcessedSequenceNumber()).contains(3L);
        assertThat(store.claimSubject("processing-group", "").orElseThrow()
            .lastProcessedSequenceNumber()).contains(4L);
        assertThat(store.globalEventSequenceState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.subjectId()).isEmpty();
            assertThat(state.lastProcessedSequenceNumber()).contains(3L);
        });
        assertThat(store.eventSequenceState("processing-group", "")).hasValueSatisfying(state -> {
            assertThat(state.subjectId()).contains("");
            assertThat(state.lastProcessedSequenceNumber()).contains(4L);
        });
    }

    @Test
    void claimSubject_preservesStatePerProcessingGroupAndSubject() {
        var store = new InMemoryEventSubjectSequenceStore();
        var firstSequence = store.claimSubject("first-group", "first-subjectId").orElseThrow();
        firstSequence.markProcessed(3L);

        assertThat(store.claimSubject("first-group", "first-subjectId").orElseThrow()
            .lastProcessedSequenceNumber()).contains(3L);
        assertThat(store.claimSubject("first-group", "second-subjectId").orElseThrow()
            .lastProcessedSequenceNumber()).isEmpty();
        assertThat(store.claimSubject("second-group", "first-subjectId").orElseThrow()
            .lastProcessedSequenceNumber()).isEmpty();
    }

    @Test
    void markProcessed_recoversFailedSequence() {
        var store = new InMemoryEventSubjectSequenceStore();
        var sequence = store
            .claimSubject("processing-group", "subjectId")
            .orElseThrow();
        assertThat(sequence.status()).isEqualTo(ACTIVE);

        sequence.markFailed(new IllegalStateException("failed"), 2);

        assertThat(sequence.status()).isEqualTo(FAILED);
        assertThat(sequence.errorCount()).isEqualTo(2);
        assertThat(sequence.lastErrorAt()).isPresent();
        assertThat(store.eventSequenceState("processing-group", "subjectId")).hasValueSatisfying(state -> {
            assertThat(state.processingGroup()).isEqualTo("processing-group");
            assertThat(state.subjectId()).contains("subjectId");
            assertThat(state.status()).isEqualTo(FAILED);
            assertThat(state.lastProcessedSequenceNumber()).isEmpty();
            assertThat(state.errorCount()).isEqualTo(2);
            assertThat(state.lastErrorMessage()).hasValueSatisfying(message ->
                assertThat(message).contains("IllegalStateException: failed")
            );
            assertThat(state.lastErrorAt()).isPresent();
        });

        sequence.markProcessed(1L);

        assertThat(sequence.status()).isEqualTo(ACTIVE);
        assertThat(sequence.lastProcessedSequenceNumber()).contains(1L);
        assertThat(sequence.errorCount()).isZero();
        assertThat(sequence.lastErrorAt()).isEmpty();
        assertThat(store.eventSequenceState("processing-group", "subjectId")).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(ACTIVE);
            assertThat(state.lastProcessedSequenceNumber()).contains(1L);
            assertThat(state.errorCount()).isZero();
            assertThat(state.lastErrorMessage()).isEmpty();
            assertThat(state.lastErrorAt()).isEmpty();
        });
    }

    @Test
    void markSkipped_advancesAbandonedSequence() {
        var store = new InMemoryEventSubjectSequenceStore();
        var sequence = store
            .claimSubject("processing-group", "subjectId")
            .orElseThrow();

        sequence.markAbandoned(2L, new IllegalStateException("abandoned"));

        assertThat(sequence.status()).isEqualTo(ABANDONED);
        assertThat(sequence.lastProcessedSequenceNumber()).contains(2L);
        assertThat(sequence.errorCount()).isZero();
        assertThat(sequence.lastErrorAt()).isPresent();

        sequence.markSkipped(3L);

        assertThat(sequence.status()).isEqualTo(ABANDONED);
        assertThat(sequence.lastProcessedSequenceNumber()).contains(3L);
        assertThat(store.eventSequenceState("processing-group", "subjectId")).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(ABANDONED);
            assertThat(state.lastProcessedSequenceNumber()).contains(3L);
            assertThat(state.errorCount()).isZero();
            assertThat(state.lastErrorMessage()).hasValueSatisfying(message ->
                assertThat(message).contains("IllegalStateException: abandoned")
            );
            assertThat(state.lastErrorAt()).isPresent();
        });
    }
}
