package app.dodb.smd.eventstore.storage.inmemory;

import app.dodb.smd.eventstore.storage.TokenState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-04-23T10:15:30Z");

    @Test
    void tokenState_whenTokenDoesNotExist_returnsEmpty() {
        assertThat(new InMemoryTokenStore().tokenState("processing-group")).isEmpty();
    }

    @Test
    void claimToken_preservesStatePerProcessingGroup() {
        var store = new InMemoryTokenStore();
        var firstClaim = store.claimToken("first").orElseThrow();
        firstClaim.markProcessed(3L);

        assertThat(store.claimToken("first").orElseThrow().lastProcessedSequenceNumber()).contains(3L);
        assertThat(store.claimToken("second").orElseThrow().lastProcessedSequenceNumber()).isEmpty();
    }

    @Test
    void markProcessed_doesNotRegressAndClearsReachedGap() {
        var store = new InMemoryTokenStore(Clock.fixed(NOW, ZoneOffset.UTC));
        var token = store.claimToken("processing-group").orElseThrow();

        token.markGapDetected(3L);
        token.markProcessed(2L);

        assertThat(token.lastProcessedSequenceNumber()).contains(2L);
        assertThat(token.lastGapDetectedAt()).contains(NOW);
        assertThat(store.tokenState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.processingGroup()).isEqualTo("processing-group");
            assertThat(state.lastProcessedSequenceNumber()).contains(2L);
            assertThat(state.lastGapDetectedAt()).contains(NOW);
            assertThat(state.gapSequenceNumber()).contains(3L);
        });

        token.markProcessed(1L);
        token.markProcessed(3L);

        assertThat(token.lastProcessedSequenceNumber()).contains(3L);
        assertThat(token.lastGapDetectedAt()).isEmpty();
        assertThat(store.tokenState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedSequenceNumber()).contains(3L);
            assertThat(state.lastGapDetectedAt()).isEmpty();
            assertThat(state.gapSequenceNumber()).isEmpty();
        });
    }

    @Test
    void markReset_movesTokenToStartAndClearsGap() {
        var store = new InMemoryTokenStore(Clock.fixed(NOW, ZoneOffset.UTC));
        var token = store.claimToken("processing-group").orElseThrow();
        token.markProcessed(5L);
        token.markGapDetected(6L);

        token.markReset(Optional.of(5L));

        assertThat(store.tokenState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedSequenceNumber()).contains(0L);
            assertThat(state.lastGapDetectedAt()).isEmpty();
            assertThat(state.gapSequenceNumber()).isEmpty();
            assertThat(state.replayUntilSequenceNumber()).contains(5L);
        });
    }

    @Test
    void markProcessed_belowReplayBoundary_keepsBoundary() {
        var store = new InMemoryTokenStore();
        var token = store.claimToken("processing-group").orElseThrow();
        token.markReset(Optional.of(5L));

        token.markProcessed(4L);

        assertThat(store.tokenState("processing-group").flatMap(TokenState::replayUntilSequenceNumber)).contains(5L);
    }

    @Test
    void markProcessed_atReplayBoundary_clearsBoundary() {
        var store = new InMemoryTokenStore();
        var token = store.claimToken("processing-group").orElseThrow();
        token.markReset(Optional.of(5L));

        token.markProcessed(5L);

        assertThat(token.replayUntilSequenceNumber()).isEmpty();
        assertThat(store.tokenState("processing-group").flatMap(TokenState::replayUntilSequenceNumber)).isEmpty();
    }
}
