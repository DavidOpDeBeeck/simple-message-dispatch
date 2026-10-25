package app.dodb.smd.eventstore.storage.inmemory;

import org.junit.jupiter.api.Test;

import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.sequence.EventSequenceStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventSequenceStoreTest {

    @Test
    void eventSequenceState_whenSequenceDoesNotExist_returnsEmpty() {
        var store = new InMemoryEventSequenceStore();

        assertThat(store.globalEventSequenceState("processing-group")).isEmpty();
        assertThat(store.eventSequenceState("processing-group", "aggregateId")).isEmpty();
    }

    @Test
    void claimGlobal_preservesStatePerProcessingGroup() {
        var store = new InMemoryEventSequenceStore();
        store.claimGlobal("first-group").orElseThrow().markProcessed(3L);

        assertThat(store.claimGlobal("first-group").orElseThrow()
            .lastProcessedPosition()).contains(3L);
        assertThat(store.claimGlobal("second-group").orElseThrow()
            .lastProcessedPosition()).isEmpty();
    }

    @Test
    void claimGlobal_doesNotAliasEmptyAggregateId() {
        var store = new InMemoryEventSequenceStore();
        store.claimGlobal("processing-group").orElseThrow().markProcessed(3L);
        store.claimAggregate("processing-group", "").orElseThrow().markProcessed(4L);

        assertThat(store.claimGlobal("processing-group").orElseThrow()
            .lastProcessedPosition()).contains(3L);
        assertThat(store.claimAggregate("processing-group", "").orElseThrow()
            .lastProcessedPosition()).contains(4L);
        assertThat(store.globalEventSequenceState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.aggregateId()).isEmpty();
            assertThat(state.lastProcessedPosition()).contains(3L);
        });
        assertThat(store.eventSequenceState("processing-group", "")).hasValueSatisfying(state -> {
            assertThat(state.aggregateId()).contains("");
            assertThat(state.lastProcessedPosition()).contains(4L);
        });
    }

    @Test
    void claimAggregate_preservesStatePerProcessingGroupAndAggregate() {
        var store = new InMemoryEventSequenceStore();
        var firstSequence = store.claimAggregate("first-group", "first-aggregateId").orElseThrow();
        firstSequence.markProcessed(3L);

        assertThat(store.claimAggregate("first-group", "first-aggregateId").orElseThrow()
            .lastProcessedPosition()).contains(3L);
        assertThat(store.claimAggregate("first-group", "second-aggregateId").orElseThrow()
            .lastProcessedPosition()).isEmpty();
        assertThat(store.claimAggregate("second-group", "first-aggregateId").orElseThrow()
            .lastProcessedPosition()).isEmpty();
    }

    @Test
    void markProcessed_recoversFailedSequence() {
        var store = new InMemoryEventSequenceStore();
        var sequence = store
            .claimAggregate("processing-group", "aggregateId")
            .orElseThrow();
        assertThat(sequence.status()).isEqualTo(ACTIVE);

        sequence.markFailed(new IllegalStateException("failed"), 2);

        assertThat(sequence.status()).isEqualTo(FAILED);
        assertThat(sequence.errorCount()).isEqualTo(2);
        assertThat(sequence.lastErrorAt()).isPresent();
        assertThat(store.eventSequenceState("processing-group", "aggregateId")).hasValueSatisfying(state -> {
            assertThat(state.processingGroup()).isEqualTo("processing-group");
            assertThat(state.aggregateId()).contains("aggregateId");
            assertThat(state.status()).isEqualTo(FAILED);
            assertThat(state.lastProcessedPosition()).isEmpty();
            assertThat(state.errorCount()).isEqualTo(2);
            assertThat(state.lastErrorMessage()).hasValueSatisfying(message ->
                assertThat(message).contains("IllegalStateException: failed")
            );
            assertThat(state.lastErrorAt()).isPresent();
        });

        sequence.markProcessed(1L);

        assertThat(sequence.status()).isEqualTo(ACTIVE);
        assertThat(sequence.lastProcessedPosition()).contains(1L);
        assertThat(sequence.errorCount()).isZero();
        assertThat(sequence.lastErrorAt()).isEmpty();
        assertThat(store.eventSequenceState("processing-group", "aggregateId")).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(ACTIVE);
            assertThat(state.lastProcessedPosition()).contains(1L);
            assertThat(state.errorCount()).isZero();
            assertThat(state.lastErrorMessage()).isEmpty();
            assertThat(state.lastErrorAt()).isEmpty();
        });
    }

    @Test
    void markSkipped_advancesAbandonedSequence() {
        var store = new InMemoryEventSequenceStore();
        var sequence = store
            .claimAggregate("processing-group", "aggregateId")
            .orElseThrow();

        sequence.markAbandoned(2L, new IllegalStateException("abandoned"));

        assertThat(sequence.status()).isEqualTo(ABANDONED);
        assertThat(sequence.lastProcessedPosition()).contains(2L);
        assertThat(sequence.errorCount()).isZero();
        assertThat(sequence.lastErrorAt()).isPresent();

        sequence.markSkipped(3L);

        assertThat(sequence.status()).isEqualTo(ABANDONED);
        assertThat(sequence.lastProcessedPosition()).contains(3L);
        assertThat(store.eventSequenceState("processing-group", "aggregateId")).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(ABANDONED);
            assertThat(state.lastProcessedPosition()).contains(3L);
            assertThat(state.errorCount()).isZero();
            assertThat(state.lastErrorMessage()).hasValueSatisfying(message ->
                assertThat(message).contains("IllegalStateException: abandoned")
            );
            assertThat(state.lastErrorAt()).isPresent();
        });
    }
}
