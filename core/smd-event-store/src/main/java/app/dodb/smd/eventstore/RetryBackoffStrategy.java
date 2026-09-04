package app.dodb.smd.eventstore;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

public interface RetryBackoffStrategy {

    Duration calculateDelay(int retryCount);

    static RetryBackoffStrategy fixed(Duration delay) {
        return new FixedRetryBackoffStrategy(delay);
    }

    static RetryBackoffStrategy exponential(Duration baseDelay, double multiplier, Duration maxDelay) {
        return new ExponentialRetryBackoffStrategy(baseDelay, multiplier, maxDelay);
    }

    static RetryBackoffStrategy linear(Duration baseDelay, Duration increment, Duration maxDelay) {
        return new LinearRetryBackoffStrategy(baseDelay, increment, maxDelay);
    }

    record FixedRetryBackoffStrategy(Duration delay) implements RetryBackoffStrategy {

        public FixedRetryBackoffStrategy {
            requireAtLeastZero(delay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireAtLeastZero(retryCount);
            return delay;
        }
    }

    record ExponentialRetryBackoffStrategy(Duration baseDelay, double multiplier, Duration maxDelay) implements RetryBackoffStrategy {

        public ExponentialRetryBackoffStrategy {
            requireAtLeastZero(baseDelay);
            requireGreaterThanZero(multiplier);
            requireAtLeastZero(maxDelay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireAtLeastZero(retryCount);
            long delayMs = (long) (baseDelay.toMillis() * Math.pow(multiplier, retryCount));
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }

    record LinearRetryBackoffStrategy(Duration baseDelay, Duration increment, Duration maxDelay) implements RetryBackoffStrategy {

        public LinearRetryBackoffStrategy {
            requireAtLeastZero(baseDelay);
            requireAtLeastZero(increment);
            requireAtLeastZero(maxDelay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireAtLeastZero(retryCount);
            long delayMs = baseDelay.toMillis() + (increment.toMillis() * retryCount);
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }

    private static Duration requireAtLeastZero(Duration duration) {
        var value = requireNonNull(duration);
        if (value.isNegative()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static int requireAtLeastZero(int value) {
        if (value < 0) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static double requireGreaterThanZero(double value) {
        if (value <= 0) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}
