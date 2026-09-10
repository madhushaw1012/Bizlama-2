package com.bizlama.api.analytics.raw;

import java.time.Instant;
import java.util.Map;

public record RawEventDelivery(
        String messageId,
        Instant publishTime,
        Instant ingestedAt,
        String sourceSubscription,
        Map<String, String> pubsubAttributes,
        Integer deliveryAttempt,
        boolean replay
) {
    public RawEventDelivery {
        pubsubAttributes = Map.copyOf(pubsubAttributes);
    }
}
