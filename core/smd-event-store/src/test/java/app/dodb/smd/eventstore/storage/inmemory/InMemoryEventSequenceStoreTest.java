package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.api.event.Subject;
import app.dodb.smd.eventstore.processing.EventSequenceId;
import org.junit.jupiter.api.Test;

import static app.dodb.smd.eventstore.processing.EventSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.processing.EventSequenceStatus.ACTIVE;
import static app.dodb.smd.eventstore.processing.EventSequenceStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventSequenceStoreTest {

    private static final EventSequenceId GLOBAL = new EventSequenceId.Global();
    private static final EventSequenceId TICKET = new EventSequenceId.OfSubject(new Subject("ticket", "ticket-1"));

    @Test
    void eventSequenceState_whenSequenceDoesNotExist_returnsEmpty() {
        var store = new InMemoryEventSequenceStore();

        assertThat(store.eventSequenceState("processing-group", GLOBAL)).isEmpty();
        assertThat(store.eventSequenceState("processing-group", TICKET)).isEmpty();
    }

    @Test
    void claim_globalSequence_preservesStatePerProcessingGroup() {
        var store = new InMemoryEventSequenceStore();
        store.claim("first-group", GLOBAL).orElseThrow().markProcessed(3L);

        assertThat(store.claim("first-group", GLOBAL).orElseThrow()
            .lastProcessedPosition()).contains(3L);
        assertThat(store.claim("second-group", GLOBAL).orElseThrow()
            .lastProcessedPosition()).isEmpty();
    }

    @Test
    void claim_globalSequence_doesNotAliasEmptySubject() {
        var store = new InMemoryEventSequenceStore();
        var emptySubject = new EventSequenceId.OfSubject(new Subject("", ""));
        store.claim("processing-group", GLOBAL).orElseThrow().markProcessed(3L);
        store.claim("processing-group", emptySubject).orElseThrow().markProcessed(4L);

        assertThat(store.eventSequenceState("processing-group", GLOBAL)).hasValueSatisfying(state -> {
            assertThat(state.sequenceId()).isEqualTo(GLOBAL);
            assertThat(state.lastProcessedPosition()).contains(3L);
        });
        assertThat(store.eventSequenceState("processing-group", emptySubject)).hasValueSatisfying(state -> {
            assertThat(state.sequenceId()).isEqualTo(emptySubject);
            assertThat(state.lastProcessedPosition()).contains(4L);
        });
    }

    @Test
    void claim_subjectSequence_preservesStatePerProcessingGroupAndSubject() {
        var store = new InMemoryEventSequenceStore();
        store.claim("first-group", TICKET).orElseThrow().markProcessed(3L);

        assertThat(store.claim("first-group", TICKET).orElseThrow()
            .lastProcessedPosition()).contains(3L);
        assertThat(store.claim("first-group", new EventSequenceId.OfSubject(new Subject("ticket", "ticket-2"))).orElseThrow()
            .lastProcessedPosition()).isEmpty();
        assertThat(store.claim("second-group", TICKET).orElseThrow()
            .lastProcessedPosition()).isEmpty();
    }

    @Test
    void claim_sameIdOfDifferentSubjectTypes_claimsSeparateSequences() {
        var store = new InMemoryEventSequenceStore();
        var account = new EventSequenceId.OfSubject(new Subject("account", "ticket-1"));
        store.claim("processing-group", TICKET).orElseThrow().markAbandoned(3L, new IllegalStateException("abandoned"));

        var accountSequence = store.claim("processing-group", account).orElseThrow();

        assertThat(accountSequence.status()).isEqualTo(ACTIVE);
        assertThat(accountSequence.lastProcessedPosition()).isEmpty();
    }

    @Test
    void markProcessed_recoversFailedSequence() {
        var store = new InMemoryEventSequenceStore();
        var sequence = store.claim("processing-group", TICKET).orElseThrow();
        assertThat(sequence.status()).isEqualTo(ACTIVE);

        sequence.markFailed(new IllegalStateException("failed"), 2);

        assertThat(sequence.status()).isEqualTo(FAILED);
        assertThat(sequence.errorCount()).isEqualTo(2);
        assertThat(sequence.lastErrorAt()).isPresent();
        assertThat(store.eventSequenceState("processing-group", TICKET)).hasValueSatisfying(state -> {
            assertThat(state.processingGroup()).isEqualTo("processing-group");
            assertThat(state.sequenceId()).isEqualTo(TICKET);
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
        assertThat(store.eventSequenceState("processing-group", TICKET)).hasValueSatisfying(state -> {
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
        var sequence = store.claim("processing-group", TICKET).orElseThrow();

        sequence.markAbandoned(2L, new IllegalStateException("abandoned"));

        assertThat(sequence.status()).isEqualTo(ABANDONED);
        assertThat(sequence.lastProcessedPosition()).contains(2L);
        assertThat(sequence.errorCount()).isZero();
        assertThat(sequence.lastErrorAt()).isPresent();

        sequence.markSkipped(3L);

        assertThat(sequence.status()).isEqualTo(ABANDONED);
        assertThat(sequence.lastProcessedPosition()).contains(3L);
        assertThat(store.eventSequenceState("processing-group", TICKET)).hasValueSatisfying(state -> {
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
