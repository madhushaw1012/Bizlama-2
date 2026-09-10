package com.bizlama.api.analytics.raw;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Cloud-neutral message processing boundary used by the Pub/Sub worker. */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.analytics.raw",
        name = "enabled",
        havingValue = "true")
public class RawEventProcessor {

    private final RawEventEnvelopeParser parser;
    private final IdempotentRawEventSink sink;
    private final Clock clock;

    public RawEventProcessor(
            RawEventEnvelopeParser parser,
            IdempotentRawEventSink sink
    ) {
        this(parser, sink, Clock.systemUTC());
    }

    RawEventProcessor(
            RawEventEnvelopeParser parser,
            IdempotentRawEventSink sink,
            Clock clock
    ) {
        this.parser = parser;
        this.sink = sink;
        this.clock = clock;
    }

    public void process(
            byte[] data,
            String messageId,
            Instant publishTime,
            String sourceSubscription,
            Map<String, String> pubsubAttributes,
            Integer deliveryAttempt,
            boolean replay
    ) {
        if (messageId == null || messageId.isBlank()) {
            throw new RawEventValidationException(
                    "Pub/Sub messageId must be present");
        }
        if (publishTime == null) {
            throw new RawEventValidationException(
                    "Pub/Sub publishTime must be present");
        }
        if (sourceSubscription == null || sourceSubscription.isBlank()) {
            throw new RawEventValidationException(
                    "Source subscription must be present");
        }
        if (pubsubAttributes == null) {
            throw new RawEventValidationException(
                    "Pub/Sub attributes must be present");
        }
        if (deliveryAttempt != null && deliveryAttempt < 1) {
            throw new RawEventValidationException(
                    "Delivery attempt must be positive when present");
        }

        RawEventEnvelope event = parser.parse(data);
        sink.insertIfAbsent(
                event,
                new RawEventDelivery(
                        messageId,
                        publishTime,
                        clock.instant(),
                        sourceSubscription.trim(),
                        pubsubAttributes,
                        deliveryAttempt,
                        replay
                ));
    }
}
