package com.bizlama.api.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bizlama.outbox.pubsub")
public record PubSubOutboxProperties(
        @DefaultValue("") String projectId,
        @DefaultValue("") String topicId,
        @DefaultValue("PT10S") Duration publishTimeout,
        @DefaultValue("PT5S") Duration shutdownTimeout
) {
    public PubSubOutboxProperties {
        requirePositive(publishTimeout, "publish-timeout");
        requirePositive(shutdownTimeout, "shutdown-timeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "bizlama.outbox.pubsub." + name + " must be positive"
            );
        }
    }
}
