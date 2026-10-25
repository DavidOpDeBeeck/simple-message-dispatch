package app.dodb.smd.api.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static app.dodb.smd.api.utils.ValidationUtils.requireNonNegative;
import static app.dodb.smd.api.utils.ValidationUtils.requirePositive;
import static java.time.Duration.ZERO;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationUtilsTest {

    @Test
    void requirePositive_positiveValues_returnsValues() {
        assertThat(requirePositive("delay", ofSeconds(1))).isEqualTo(ofSeconds(1));
        assertThat(requirePositive("size", 1)).isEqualTo(1);
        assertThat(requirePositive("multiplier", 0.5)).isEqualTo(0.5);
    }

    @Test
    void requirePositive_zeroDuration_failsWithNameAndValue() {
        assertThatThrownBy(() -> requirePositive("delay", ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("delay must be greater than zero; got PT0S");
    }

    @Test
    void requirePositive_negativeDuration_fails() {
        assertThatThrownBy(() -> requirePositive("delay", ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("delay must be greater than zero; got PT-1S");
    }

    @Test
    void requirePositive_zeroInteger_failsWithNameAndValue() {
        assertThatThrownBy(() -> requirePositive("size", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("size must be greater than zero; got 0");
    }

    @Test
    void requirePositive_nanDouble_fails() {
        assertThatThrownBy(() -> requirePositive("multiplier", Double.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("multiplier must be greater than zero; got NaN");
    }

    @Test
    void requirePositive_missingDuration_fails() {
        assertThatThrownBy(() -> requirePositive("delay", (Duration) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void requireNonNegative_zeroValues_returnsValues() {
        assertThat(requireNonNegative("delay", ZERO)).isEqualTo(ZERO);
        assertThat(requireNonNegative("retries", 0)).isZero();
    }

    @Test
    void requireNonNegative_negativeDuration_failsWithNameAndValue() {
        assertThatThrownBy(() -> requireNonNegative("delay", ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("delay must be zero or greater; got PT-1S");
    }

    @Test
    void requireNonNegative_negativeInteger_failsWithNameAndValue() {
        assertThatThrownBy(() -> requireNonNegative("retries", -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("retries must be zero or greater; got -1");
    }

    @Test
    void requireNonNegative_missingDuration_fails() {
        assertThatThrownBy(() -> requireNonNegative("delay", (Duration) null)).isInstanceOf(NullPointerException.class);
    }
}
