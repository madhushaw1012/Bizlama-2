package com.bizlama.api.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/no-op sink. Dispatch is disabled by default, so events are retained
 * until an operator explicitly enables this sink or supplies another one.
 */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.outbox",
        name = "publisher",
        havingValue = "local",
        matchIfMissing = true)
public class LocalOutboxPublisher implements OutboxPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(LocalOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.debug(
                "Locally acknowledged outbox event id={} type={} schema={}",
                event.eventId(),
                event.eventType(),
                event.schemaVersion());
    }
}
