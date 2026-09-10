package com.bizlama.api.analytics.raw;

/**
 * Persists a validated raw event using {@code eventId} as its idempotency key.
 */
@FunctionalInterface
public interface IdempotentRawEventSink {

    void insertIfAbsent(RawEventEnvelope event, RawEventDelivery delivery);
}
