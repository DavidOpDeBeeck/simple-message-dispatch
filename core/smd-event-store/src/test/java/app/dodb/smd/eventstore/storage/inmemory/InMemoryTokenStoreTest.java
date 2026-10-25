package app.dodb.smd.eventstore.storage.inmemory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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

        assertThat(store.claimToken("first").orElseThrow().lastProcessedPosition()).contains(3L);
        assertThat(store.claimToken("second").orElseThrow().lastProcessedPosition()).isEmpty();
    }

    @Test
    void markProcessed_doesNotRegressAndClearsReachedGap() {
        var store = new InMemoryTokenStore(Clock.fixed(NOW, ZoneOffset.UTC));
        var token = store.claimToken("processing-group").orElseThrow();

        token.markGapDetected(3L);
        token.markProcessed(2L);

        assertThat(token.lastProcessedPosition()).contains(2L);
        assertThat(token.lastGapDetectedAt()).contains(NOW);
        assertThat(store.tokenState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.processingGroup()).isEqualTo("processing-group");
            assertThat(state.lastProcessedPosition()).contains(2L);
            assertThat(state.lastGapDetectedAt()).contains(NOW);
            assertThat(state.gapPosition()).contains(3L);
        });

        token.markProcessed(1L);
        token.markProcessed(3L);

        assertThat(token.lastProcessedPosition()).contains(3L);
        assertThat(token.lastGapDetectedAt()).isEmpty();
        assertThat(store.tokenState("processing-group")).hasValueSatisfying(state -> {
            assertThat(state.lastProcessedPosition()).contains(3L);
            assertThat(state.lastGapDetectedAt()).isEmpty();
            assertThat(state.gapPosition()).isEmpty();
        });
    }
}
