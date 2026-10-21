package app.dodb.smd.eventstore;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingProgressTest {

    @Test
    void remaining_withPositionBehindHead_returnsDifference() {
        var progress = new ProcessingProgress("ticket-view", 4L, 10L, Optional.empty());

        assertThat(progress.remaining()).isEqualTo(6L);
    }

    @Test
    void remaining_withPositionAheadOfHead_returnsZero() {
        var progress = new ProcessingProgress("ticket-view", 10L, 8L, Optional.empty());

        assertThat(progress.remaining()).isZero();
    }

    @Test
    void replaying_withPositionBelowReplayBoundary_returnsTrue() {
        var progress = new ProcessingProgress("ticket-view", 4L, 10L, Optional.of(5L));

        assertThat(progress.replaying()).isTrue();
    }

    @Test
    void replaying_withPositionAtReplayBoundary_returnsFalse() {
        var progress = new ProcessingProgress("ticket-view", 5L, 10L, Optional.of(5L));

        assertThat(progress.replaying()).isFalse();
    }

    @Test
    void replaying_withoutReplayBoundary_returnsFalse() {
        var progress = new ProcessingProgress("ticket-view", 0L, 10L, Optional.empty());

        assertThat(progress.replaying()).isFalse();
    }
}
