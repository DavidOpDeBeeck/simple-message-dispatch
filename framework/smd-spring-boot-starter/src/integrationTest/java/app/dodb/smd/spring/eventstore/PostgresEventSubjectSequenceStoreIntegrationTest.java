package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.sequence.EventSequenceState;
import app.dodb.smd.eventstore.sequence.EventSubjectSequenceStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.ABANDONED;
import static app.dodb.smd.eventstore.sequence.EventSubjectSequenceStatus.FAILED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.time.Duration.ofNanos;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresEventSubjectSequenceStoreIntegrationTest {

    @Test
    void eventSequenceState_readsGlobalAndSubjectSnapshotsWithoutInitializingRows() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var sequenceStore = fixture.bean(EventSubjectSequenceStore.class);
            var transactionProvider = fixture.bean(TransactionProvider.class);
            var processingGroup = "diagnostic-processing-group";

            assertThat(sequenceStore.globalEventSequenceState(processingGroup)).isEmpty();
            assertThat(sequenceStore.eventSequenceState(processingGroup, "")).isEmpty();

            transactionProvider.doInNewTransaction(() -> {
                sequenceStore.claimGlobal(processingGroup)
                    .orElseThrow()
                    .markFailed(new IllegalStateException("global failed"), 2);
                sequenceStore.claimSubject(processingGroup, "")
                    .orElseThrow()
                    .markAbandoned(4L, new IllegalStateException("subject abandoned"));
            });

            assertThat(sequenceStore.globalEventSequenceState(processingGroup))
                .hasValueSatisfying(state -> assertFailedGlobalState(state, processingGroup));
            assertThat(sequenceStore.eventSequenceState(processingGroup, "")).hasValueSatisfying(state -> {
                assertThat(state.processingGroup()).isEqualTo(processingGroup);
                assertThat(state.subjectId()).contains("");
                assertThat(state.status()).isEqualTo(ABANDONED);
                assertThat(state.lastProcessedSequenceNumber()).contains(4L);
                assertThat(state.errorCount()).isZero();
                assertThat(state.lastErrorMessage()).hasValueSatisfying(message ->
                    assertThat(message).contains("IllegalStateException: subject abandoned")
                );
                assertThat(state.lastErrorAt()).isPresent();
            });
        }
    }

    @Test
    void claimGlobal_persistsNullSubjectWithoutAliasingEmptySubject() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var sequenceStore = fixture.bean(EventSubjectSequenceStore.class);
            var transactionProvider = fixture.bean(TransactionProvider.class);
            var processingGroup = "global-persistence-processing-group";

            transactionProvider.doInNewTransaction(() -> sequenceStore.claimGlobal(processingGroup)
                .orElseThrow()
                .markProcessed(3L));
            transactionProvider.doInNewTransaction(() -> sequenceStore.claimSubject(processingGroup, "")
                .orElseThrow()
                .markProcessed(4L));

            var globalSequence = transactionProvider.doInNewTransaction(() -> sequenceStore.claimGlobal(processingGroup));
            assertThat(globalSequence.flatMap(sequence -> sequence.lastProcessedSequenceNumber())).contains(3L);

            var emptySubjectSequence = transactionProvider.doInNewTransaction(() -> sequenceStore.claimSubject(processingGroup, ""));
            assertThat(emptySubjectSequence.flatMap(sequence -> sequence.lastProcessedSequenceNumber())).contains(4L);
        }
    }

    @Test
    void claimGlobal_whenAlreadyClaimed_returnsEmptyWithoutBlockingThenCanClaimAfterCommit() throws Exception {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start();
             var executor = Executors.newFixedThreadPool(2)) {
            var sequenceStore = fixture.bean(EventSubjectSequenceStore.class);
            var transactionProvider = fixture.bean(TransactionProvider.class);
            var processingGroup = "contended-global-processing-group";
            var firstClaimed = new CountDownLatch(1);
            var releaseFirstClaim = new CountDownLatch(1);

            var firstClaim = executor.submit(() -> transactionProvider.doInNewTransaction(() -> {
                assertThat(sequenceStore.claimGlobal(processingGroup)).isPresent();
                firstClaimed.countDown();
                awaitLatch(releaseFirstClaim);
                return null;
            }));

            awaitLatch(firstClaimed);

            var startedAt = System.nanoTime();
            var secondClaim = transactionProvider.doInNewTransaction(() -> sequenceStore.claimGlobal(processingGroup));
            var elapsed = ofNanos(System.nanoTime() - startedAt);

            assertThat(secondClaim).isEmpty();
            assertThat(elapsed).isLessThan(ofSeconds(1));

            releaseFirstClaim.countDown();
            firstClaim.get(5, SECONDS);

            assertThat(transactionProvider.doInNewTransaction(() -> sequenceStore.claimGlobal(processingGroup))).isPresent();
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void assertFailedGlobalState(EventSequenceState state, String processingGroup) {
        assertThat(state.processingGroup()).isEqualTo(processingGroup);
        assertThat(state.subjectId()).isEmpty();
        assertThat(state.status()).isEqualTo(FAILED);
        assertThat(state.lastProcessedSequenceNumber()).isEmpty();
        assertThat(state.errorCount()).isEqualTo(2);
        assertThat(state.lastErrorMessage()).hasValueSatisfying(message ->
            assertThat(message).contains("IllegalStateException: global failed")
        );
        assertThat(state.lastErrorAt()).isPresent();
    }
}
