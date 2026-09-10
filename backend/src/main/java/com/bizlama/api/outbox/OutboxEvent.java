package com.bizlama.api.outbox;

import java.time.Instant;

/**
 * Immutable event envelope plus its current delivery state.
 *
 * <p>{@code eventId} is the stable downstream idempotency key. Replaying an
 * event never changes it.</p>
 */
public record OutboxEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String kitchenId,
        String entityType,
        String entityId,
        Instant occurredAt,
        Instant recordedAt,
        String correlationId,
        String causationId,
        String deduplicationKey,
        String payloadJson,
        String sourceMetadataJson,
        OutboxState state,
        int attemptCount,
        Instant nextAttemptAt,
        Instant publishedAt,
        String lastError
) {
}
