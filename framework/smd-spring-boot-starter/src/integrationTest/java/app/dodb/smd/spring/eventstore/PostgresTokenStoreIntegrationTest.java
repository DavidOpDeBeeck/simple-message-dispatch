package app.dodb.smd.spring.eventstore;

import app.dodb.smd.api.framework.TransactionProvider;
import app.dodb.smd.eventstore.store.TokenState;
import app.dodb.smd.eventstore.store.TokenStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.SCHEDULING_DISABLED;
import static app.dodb.smd.spring.eventstore.EventStoreTestFixture.eventStoreTestFixture;
import static java.time.Duration.ofNanos;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresTokenStoreIntegrationTest {

    @Test
    void claimToken_whenAlreadyClaimed_returnsEmptyWithoutBlockingThenCanClaimAfterCommit() throws Exception {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start();
             var executor = Executors.newFixedThreadPool(2)) {
            var tokenStore = fixture.bean(TokenStore.class);
            var transactionProvider = fixture.bean(TransactionProvider.class);

            var processingGroup = "contended-processing-group";
            var firstClaimed = new CountDownLatch(1);
            var releaseFirstClaim = new CountDownLatch(1);

            var firstClaim = executor.submit(() -> transactionProvider.doInNewTransaction(() -> {
                assertThat(tokenStore.claimToken(processingGroup)).isPresent();
                firstClaimed.countDown();
                awaitLatch(releaseFirstClaim);
                return null;
            }));

            awaitLatch(firstClaimed);

            var startedAt = System.nanoTime();
            var secondClaim = transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup));
            var elapsed = ofNanos(System.nanoTime() - startedAt);

            assertThat(secondClaim).isEmpty();
            assertThat(elapsed).isLessThan(ofSeconds(1));

            releaseFirstClaim.countDown();
            firstClaim.get(5, SECONDS);

            assertThat(transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup))).isPresent();
        }
    }

    @Test
    void markProcessed_updatesTokenAndClearsGapState() {
        try (var fixture = eventStoreTestFixture()
            .properties(SCHEDULING_DISABLED)
            .start()) {
            var tokenStore = fixture.bean(TokenStore.class);
            var transactionProvider = fixture.bean(TransactionProvider.class);
            var processingGroup = "token-state-processing-group";

            assertThat(tokenStore.tokenState(processingGroup)).isEmpty();

            transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup)
                .ifPresent(token -> {
                    token.markProcessed(5L);
                    token.markGapDetected(6L);
                }));

            assertThat(tokenStore.tokenState(processingGroup)).hasValueSatisfying(state -> {
                assertThat(state.processingGroup()).isEqualTo(processingGroup);
                assertThat(state.lastProcessedSequenceNumber()).contains(5L);
                assertThat(state.lastGapDetectedAt()).isPresent();
                assertThat(state.gapSequenceNumber()).contains(6L);
            });

            transactionProvider.doInNewTransaction(() -> tokenStore.claimToken(processingGroup)
                .ifPresent(token -> token.markProcessed(6L)));

            assertThat(fixture.tokenState(processingGroup)
                .flatMap(TokenState::lastProcessedSequenceNumber)).contains(6L);
            assertThat(fixture.tokenState(processingGroup)
                .flatMap(TokenState::gapSequenceNumber)).isEmpty();
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
}
