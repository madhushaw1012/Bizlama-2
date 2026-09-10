package com.bizlama.api.outbox;

import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class OutboxRetryPolicy {

    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public OutboxRetryPolicy(OutboxProperties properties) {
        this.maxAttempts = properties.maxAttempts();
        this.baseBackoff = requirePositive(
                properties.baseBackoff(), "base-backoff");
        this.maxBackoff = requirePositive(
                properties.maxBackoff(), "max-backoff");
    }

    public boolean exhausted(int attemptCount) {
        return attemptCount >= maxAttempts;
    }

    /**
     * The first failed attempt waits the base delay; every later failed
     * attempt doubles it up to the configured cap.
     */
    public Duration delayAfter(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException(
                    "attemptCount must be at least one");
        }

        if (baseBackoff.compareTo(maxBackoff) >= 0) {
            return maxBackoff;
        }

        long multiplier = 1L << Math.min(attemptCount - 1, 62);
        Duration candidate;
        try {
            candidate = baseBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            return maxBackoff;
        }
        return candidate.compareTo(maxBackoff) > 0
                ? maxBackoff
                : candidate;
    }

    private static Duration requirePositive(
            Duration duration,
            String propertyName) {

        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "bizlama.outbox." + propertyName + " must be positive");
        }
        return duration;
    }
}
