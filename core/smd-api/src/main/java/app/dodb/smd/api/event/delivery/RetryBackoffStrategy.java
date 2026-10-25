package app.dodb.smd.api.event.delivery;

import java.time.Duration;

import static app.dodb.smd.api.utils.ValidationUtils.requireNonNegative;
import static app.dodb.smd.api.utils.ValidationUtils.requirePositive;

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
            requireNonNegative("Retry backoff delay", delay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireNonNegative("Retry backoff retryCount", retryCount);
            return delay;
        }
    }

    record ExponentialRetryBackoffStrategy(Duration baseDelay, double multiplier, Duration maxDelay) implements RetryBackoffStrategy {

        public ExponentialRetryBackoffStrategy {
            requireNonNegative("Retry backoff baseDelay", baseDelay);
            requirePositive("Retry backoff multiplier", multiplier);
            requireNonNegative("Retry backoff maxDelay", maxDelay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireNonNegative("Retry backoff retryCount", retryCount);
            long delayMs = (long) (baseDelay.toMillis() * Math.pow(multiplier, retryCount));
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }

    record LinearRetryBackoffStrategy(Duration baseDelay, Duration increment, Duration maxDelay) implements RetryBackoffStrategy {

        public LinearRetryBackoffStrategy {
            requireNonNegative("Retry backoff baseDelay", baseDelay);
            requireNonNegative("Retry backoff increment", increment);
            requireNonNegative("Retry backoff maxDelay", maxDelay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireNonNegative("Retry backoff retryCount", retryCount);
            long delayMs = baseDelay.toMillis() + (increment.toMillis() * retryCount);
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }
}
