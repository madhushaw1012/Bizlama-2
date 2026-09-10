package com.bizlama.api.outbox;

@FunctionalInterface
public interface OutboxPublisher {

    /**
     * Publishes one immutable envelope. Implementations must use
     * {@link OutboxEvent#eventId()} for downstream idempotency.
     */
    void publish(OutboxEvent event) throws Exception;
}
