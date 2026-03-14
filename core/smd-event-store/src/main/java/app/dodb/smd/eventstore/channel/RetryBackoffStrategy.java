package app.dodb.smd.eventstore.channel;

import java.time.Duration;

public interface RetryBackoffStrategy {

    Duration calculateDelay(int retryCount);

    static RetryBackoffStrategy fixed(Duration delay) {
        return _ -> delay;
    }

    static RetryBackoffStrategy exponential(Duration initialDelay, double multiplier, Duration maxDelay) {
        return retryCount -> {
            long delayMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, retryCount));
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        };
    }

    static RetryBackoffStrategy linear(Duration initialDelay, Duration increment, Duration maxDelay) {
        return retryCount -> {
            long delayMs = initialDelay.toMillis() + (increment.toMillis() * retryCount);
            return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
        };
    }
}
