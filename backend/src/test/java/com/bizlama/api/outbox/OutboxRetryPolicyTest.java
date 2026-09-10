package com.bizlama.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

    @Test
    void doublesDelayUntilCapAndStopsAtMaximumAttempts() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(properties());

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(40)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.exhausted(2)).isFalse();
        assertThat(policy.exhausted(3)).isTrue();
    }

    private static OutboxProperties properties() {
        return new OutboxProperties(
                false,
                "local",
                10,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                "test-worker");
    }
}
