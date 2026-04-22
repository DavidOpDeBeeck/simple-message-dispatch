package app.dodb.smd.eventstore.channel;

import org.junit.jupiter.api.Test;

import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.exponential;
import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.fixed;
import static app.dodb.smd.eventstore.channel.RetryBackoffStrategy.linear;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryBackoffStrategyTest {

    @Test
    void fixed_rejectsMissingDelay() {
        assertThatThrownBy(() -> fixed(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fixed_rejectsNegativeDelay() {
        assertThatThrownBy(() -> fixed(ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixed_rejectsNegativeRetryCount() {
        assertThatThrownBy(() -> fixed(ZERO).calculateDelay(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linear_rejectsMissingArguments() {
        assertThatThrownBy(() -> linear(null, ZERO, ZERO))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> linear(ZERO, null, ZERO))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> linear(ZERO, ZERO, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void linear_rejectsNegativeArguments() {
        assertThatThrownBy(() -> linear(ofMillis(-1), ZERO, ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linear(ZERO, ofMillis(-1), ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linear(ZERO, ZERO, ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linear_rejectsNegativeRetryCount() {
        assertThatThrownBy(() -> linear(ZERO, ZERO, ZERO).calculateDelay(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exponential_rejectsMissingArguments() {
        assertThatThrownBy(() -> exponential(null, 1.0, ZERO))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> exponential(ZERO, 1.0, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void exponential_rejectsNegativeArguments() {
        assertThatThrownBy(() -> exponential(ofMillis(-1), 1.0, ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exponential(ZERO, 1.0, ofMillis(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exponential_rejectsNonPositiveMultiplier() {
        assertThatThrownBy(() -> exponential(ZERO, 0, ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exponential_rejectsNegativeRetryCount() {
        assertThatThrownBy(() -> exponential(ZERO, 1.0, ZERO).calculateDelay(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
