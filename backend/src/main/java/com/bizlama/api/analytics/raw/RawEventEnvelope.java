package com.bizlama.api.analytics.raw;

import java.time.Instant;

/**
 * Validated, immutable representation of the complete outbox delivery
 * envelope. JSON payloads are deliberately retained as JSON strings so the
 * raw warehouse table remains lossless and schema-neutral.
 */
public record RawEventEnvelope(
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
        String state,
        int attemptCount,
        Instant nextAttemptAt,
        Instant publishedAt,
        String lastError,
        String envelopeJson
) {
}
