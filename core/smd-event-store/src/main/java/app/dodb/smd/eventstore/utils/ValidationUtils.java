package app.dodb.smd.eventstore.utils;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

public final class ValidationUtils {

    private ValidationUtils() {
    }

    public static Duration requireGreaterThanZero(Duration duration) {
        var value = requireNonNull(duration);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    public static int requireGreaterThanZero(Integer value) {
        int result = requireNonNull(value);
        if (result <= 0) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    public static double requireGreaterThanZero(Double value) {
        double result = requireNonNull(value);
        if (result <= 0) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    public static Duration requireAtLeastZero(Duration duration) {
        var value = requireNonNull(duration);
        if (value.isNegative()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    public static int requireAtLeastZero(Integer value) {
        int result = requireNonNull(value);
        if (result < 0) {
            throw new IllegalArgumentException();
        }
        return result;
    }
}
