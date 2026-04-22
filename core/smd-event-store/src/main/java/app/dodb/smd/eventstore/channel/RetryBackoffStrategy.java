package app.dodb.smd.eventstore.channel;

import java.time.Duration;

import static app.dodb.smd.eventstore.utils.ValidationUtils.requireAtLeastZero;
import static app.dodb.smd.eventstore.utils.ValidationUtils.requireGreaterThanZero;

public interface RetryBackoffStrategy {

    Duration calculateDelay(int retryCount);

    static RetryBackoffStrategy fixed(Duration delay) {
        return new FixedRetryBackoffStrategy(delay);
    }

    static RetryBackoffStrategy exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
        return new ExponentialRetryBackoffStrategy(initialDelay, multiplier, maxDelay);
    }

    static RetryBackoffStrategy linear(Duration initialDelay, Duration increment, Duration maxDelay) {
        return new LinearRetryBackoffStrategy(initialDelay, increment, maxDelay);
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

    record ExponentialRetryBackoffStrategy(Duration initialDelay, double multiplier, Duration maxDelay) implements RetryBackoffStrategy {

        public ExponentialRetryBackoffStrategy {
            requireAtLeastZero(initialDelay);
            requireGreaterThanZero(multiplier);
            requireAtLeastZero(maxDelay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireAtLeastZero(retryCount);
            long delayMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, retryCount));
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }

    record LinearRetryBackoffStrategy(Duration initialDelay, Duration increment, Duration maxDelay) implements RetryBackoffStrategy {

        public LinearRetryBackoffStrategy {
            requireAtLeastZero(initialDelay);
            requireAtLeastZero(increment);
            requireAtLeastZero(maxDelay);
        }

        @Override
        public Duration calculateDelay(int retryCount) {
            requireAtLeastZero(retryCount);
            long delayMs = initialDelay.toMillis() + (increment.toMillis() * retryCount);
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        }
    }
}
