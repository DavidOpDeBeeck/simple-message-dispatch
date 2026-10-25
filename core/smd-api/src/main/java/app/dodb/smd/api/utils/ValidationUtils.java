package app.dodb.smd.api.utils;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

public class ValidationUtils {

    public static Duration requirePositive(String name, Duration value) {
        requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero; got " + value);
        }
        return value;
    }

    public static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero; got " + value);
        }
        return value;
    }

    public static double requirePositive(String name, double value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException(name + " must be greater than zero; got " + value);
        }
        return value;
    }

    public static Duration requireNonNegative(String name, Duration value) {
        requireNonNull(value);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must be zero or greater; got " + value);
        }
        return value;
    }

    public static int requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be zero or greater; got " + value);
        }
        return value;
    }
}
