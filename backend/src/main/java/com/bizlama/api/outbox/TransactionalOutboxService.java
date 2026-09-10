package com.bizlama.api.outbox;

import com.bizlama.api.config.JdbcTimestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and leases transactional outbox events.
 *
 * <p>{@link #append(OutboxEventDraft)} deliberately requires an existing
 * transaction. The caller must invoke it before committing the operational
 * mutation so both writes succeed or roll back together.</p>
 */
@Service
public class TransactionalOutboxService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final String EVENT_SELECT = """
            SELECT COALESCE(event_id, id) AS resolved_event_id,
                   event_type,
                   schema_version,
                   kitchen_id,
                   COALESCE(entity_type, aggregate_type) AS resolved_entity_type,
                   COALESCE(entity_id, aggregate_id) AS resolved_entity_id,
                   occurred_at,
                   COALESCE(recorded_at, occurred_at) AS resolved_recorded_at,
                   correlation_id,
                   causation_id,
                   COALESCE(deduplication_key, id) AS resolved_deduplication_key,
                   payload_json,
                   COALESCE(source_metadata_json, '{}') AS resolved_source_metadata_json,
                   state,
                   attempt_count,
                   next_attempt_at,
                   published_at,
                   last_error
            FROM analytics_outbox
            """;

    private final JdbcClient jdbc;
    private final CanonicalJson canonicalJson;
    private final Clock clock;
    private final OutboxProperties properties;
    private final OutboxRetryPolicy retryPolicy;
    private final String workerId;

    public TransactionalOutboxService(
            JdbcClient jdbc,
            CanonicalJson canonicalJson,
            Clock clock,
            OutboxProperties properties,
            OutboxRetryPolicy retryPolicy) {

        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
        this.properties = properties;
        this.retryPolicy = retryPolicy;

        if (properties.batchSize() < 1) {
            throw new IllegalArgumentException(
                    "bizlama.outbox.batch-size must be positive");
        }
        requirePositive(properties.leaseDuration(), "lease-duration");

        String configuredWorker = optional(properties.workerId());
        this.workerId = configuredWorker == null
                ? "outbox-" + UUID.randomUUID()
                : limited(configuredWorker, 150, "workerId");
    }

    /**
     * Adds one event to the caller's transaction. A repeated deduplication key
     * returns the existing event when its immutable content agrees.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(OutboxEventDraft draft) {
        Objects.requireNonNull(draft, "draft");

        Instant recordedAt = clock.instant();
        Instant occurredAt = draft.occurredAt() == null
                ? recordedAt
                : draft.occurredAt();
        String eventId = limited(
                optional(draft.eventId()) == null
                        ? UUID.randomUUID().toString()
                        : draft.eventId().trim(),
                100,
                "eventId");
        String eventType = required(draft.eventType(), 80, "eventType");
        int schemaVersion = draft.schemaVersion() == null
                ? 1
                : draft.schemaVersion();
        if (schemaVersion < 1) {
            throw new IllegalArgumentException(
                    "schemaVersion must be at least one");
        }
        String kitchenId = required(draft.kitchenId(), 80, "kitchenId");
        String entityType = required(draft.entityType(), 80, "entityType");
        String entityId = required(draft.entityId(), 120, "entityId");
        String correlationId = limitedOptional(
                draft.correlationId(), 120, "correlationId");
        String causationId = limitedOptional(
                draft.causationId(), 120, "causationId");
        String deduplicationKey = required(
                draft.deduplicationKey(), 200, "deduplicationKey");
        String payloadJson = canonicalJson.write(draft.payload());
        String sourceMetadataJson = canonicalJson.write(
                draft.sourceMetadata());

        Optional<OutboxEvent> duplicate = findByDeduplicationKey(
                kitchenId, deduplicationKey);
        if (duplicate.isPresent()) {
            verifyDuplicate(
                    duplicate.get(),
                    eventType,
                    schemaVersion,
                    entityType,
                    entityId,
                    payloadJson);
            return duplicate.get();
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", eventId);
        parameters.put("eventType", eventType);
        parameters.put("schemaVersion", schemaVersion);
        parameters.put("kitchenId", kitchenId);
        parameters.put("entityType", entityType);
        parameters.put("entityId", entityId);
        parameters.put("occurredAt", JdbcTimestamp.utc(occurredAt));
        parameters.put("recordedAt", JdbcTimestamp.utc(recordedAt));
        parameters.put("correlationId", correlationId);
        parameters.put("causationId", causationId);
        parameters.put("deduplicationKey", deduplicationKey);
        parameters.put("payloadJson", payloadJson);
        parameters.put("sourceMetadataJson", sourceMetadataJson);

        jdbc.sql("""
                        INSERT INTO analytics_outbox
                        (id, event_id, event_type, schema_version, kitchen_id,
                         aggregate_type, aggregate_id, entity_type, entity_id,
                         occurred_at, recorded_at, correlation_id, causation_id,
                         deduplication_key, payload_json, source_metadata_json,
                         state, attempt_count, next_attempt_at)
                        VALUES
                        (:id, :id, :eventType, :schemaVersion, :kitchenId,
                         :entityType, :entityId, :entityType, :entityId,
                         :occurredAt, :recordedAt, :correlationId, :causationId,
                         :deduplicationKey, :payloadJson, :sourceMetadataJson,
                         'PENDING', 0, :recordedAt)
                        """)
                .params(parameters)
                .update();

        return find(eventId).orElseThrow(() ->
                new IllegalStateException(
                        "Inserted outbox event could not be read: " + eventId));
    }

    /**
     * Atomically claims a bounded batch. PostgreSQL row locks plus SKIP LOCKED
     * permit multiple workers without duplicate concurrent claims; the lease
     * makes a crashed worker's rows eligible again later.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch() {
        Instant now = clock.instant();
        expireExhausted(now);

        List<String> ids = jdbc.sql("""
                        SELECT id
                        FROM analytics_outbox
                        WHERE published_at IS NULL
                          AND attempt_count < :maxAttempts
                          AND (
                              (state = 'PENDING'
                               AND next_attempt_at <= :now)
                              OR
                              (state = 'IN_FLIGHT'
                               AND COALESCE(
                                   lease_expires_at,
                                   next_attempt_at,
                                   recorded_at,
                                   occurred_at) <= :now)
                          )
                        ORDER BY COALESCE(
                                     next_attempt_at,
                                     lease_expires_at,
                                     recorded_at,
                                     occurred_at),
                                 occurred_at,
                                 id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("maxAttempts", properties.maxAttempts())
                .param("now", JdbcTimestamp.utc(now))
                .param("batchSize", properties.batchSize())
                .query(String.class)
                .list();

        if (ids.isEmpty()) {
            return List.of();
        }

        Instant leaseExpiresAt = now.plus(properties.leaseDuration());
        jdbc.sql("""
                        UPDATE analytics_outbox
                        SET state = 'IN_FLIGHT',
                            attempt_count = attempt_count + 1,
                            lease_owner = :workerId,
                            lease_expires_at = :leaseExpiresAt
                        WHERE id IN (:ids)
                        """)
                .param("workerId", workerId)
                .param("leaseExpiresAt", JdbcTimestamp.utc(leaseExpiresAt))
                .param("ids", ids)
                .update();

        return jdbc.sql(EVENT_SELECT + """
                        WHERE id IN (:ids)
                          AND state = 'IN_FLIGHT'
                          AND lease_owner = :workerId
                        ORDER BY occurred_at, id
                        """)
                .param("ids", ids)
                .param("workerId", workerId)
                .query(TransactionalOutboxService::event)
                .list();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(String eventId) {
        String id = required(eventId, 100, "eventId");
        int updated = jdbc.sql("""
                        UPDATE analytics_outbox
                        SET state = 'PUBLISHED',
                            published_at = :publishedAt,
                            next_attempt_at = NULL,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            last_error = NULL
                        WHERE id = :id
                          AND state = 'IN_FLIGHT'
                          AND lease_owner = :workerId
                        """)
                .param("publishedAt", JdbcTimestamp.utc(clock.instant()))
                .param("id", id)
                .param("workerId", workerId)
                .update();
        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String eventId, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String id = required(eventId, 100, "eventId");

        Integer attemptCount = jdbc.sql("""
                        SELECT attempt_count
                        FROM analytics_outbox
                        WHERE id = :id
                          AND state = 'IN_FLIGHT'
                          AND lease_owner = :workerId
                        FOR UPDATE
                        """)
                .param("id", id)
                .param("workerId", workerId)
                .query(Integer.class)
                .optional()
                .orElse(null);

        if (attemptCount == null) {
            return false;
        }

        boolean exhausted = retryPolicy.exhausted(attemptCount);
        Instant nextAttemptAt = exhausted
                ? null
                : clock.instant().plus(retryPolicy.delayAfter(attemptCount));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("state", exhausted
                ? OutboxState.DEAD_LETTER.name()
                : OutboxState.PENDING.name());
        parameters.put("nextAttemptAt", JdbcTimestamp.utc(nextAttemptAt));
        parameters.put("lastError", errorMessage(failure));
        parameters.put("id", id);
        parameters.put("workerId", workerId);

        int updated = jdbc.sql("""
                        UPDATE analytics_outbox
                        SET state = :state,
                            next_attempt_at = :nextAttemptAt,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            last_error = :lastError
                        WHERE id = :id
                          AND state = 'IN_FLIGHT'
                          AND lease_owner = :workerId
                        """)
                .params(parameters)
                .update();
        return updated == 1;
    }

    /**
     * Explicitly requeues a terminal event. Envelope, event id, and dedup key
     * are retained so consumers can safely identify a duplicate delivery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replay(String eventId) {
        String id = required(eventId, 100, "eventId");
        int updated = jdbc.sql("""
                        UPDATE analytics_outbox
                        SET state = 'PENDING',
                            attempt_count = 0,
                            next_attempt_at = :now,
                            published_at = NULL,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            last_error = NULL
                        WHERE id = :id
                          AND state IN ('PUBLISHED', 'DEAD_LETTER')
                        """)
                .param("now", JdbcTimestamp.utc(clock.instant()))
                .param("id", id)
                .update();
        return updated == 1;
    }

    public Optional<OutboxEvent> find(String eventId) {
        String id = required(eventId, 100, "eventId");
        return jdbc.sql(EVENT_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(TransactionalOutboxService::event)
                .optional();
    }

    String workerId() {
        return workerId;
    }

    private Optional<OutboxEvent> findByDeduplicationKey(
            String kitchenId,
            String deduplicationKey) {

        return jdbc.sql(EVENT_SELECT + """
                        WHERE kitchen_id = :kitchenId
                          AND COALESCE(deduplication_key, id) = :deduplicationKey
                        """)
                .param("kitchenId", kitchenId)
                .param("deduplicationKey", deduplicationKey)
                .query(TransactionalOutboxService::event)
                .optional();
    }

    private void expireExhausted(Instant now) {
        jdbc.sql("""
                        UPDATE analytics_outbox
                        SET state = 'DEAD_LETTER',
                            next_attempt_at = NULL,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            last_error = COALESCE(
                                last_error,
                                'Delivery lease expired after final attempt')
                        WHERE published_at IS NULL
                          AND attempt_count >= :maxAttempts
                          AND (
                              state = 'PENDING'
                              OR
                              (state = 'IN_FLIGHT'
                               AND COALESCE(
                                   lease_expires_at,
                                   next_attempt_at,
                                   recorded_at,
                                   occurred_at) <= :now)
                          )
                        """)
                .param("maxAttempts", properties.maxAttempts())
                .param("now", JdbcTimestamp.utc(now))
                .update();
    }

    private static OutboxEvent event(ResultSet result, int rowNumber)
            throws SQLException {

        return new OutboxEvent(
                result.getString("resolved_event_id"),
                result.getString("event_type"),
                result.getInt("schema_version"),
                result.getString("kitchen_id"),
                result.getString("resolved_entity_type"),
                result.getString("resolved_entity_id"),
                instant(result, "occurred_at"),
                instant(result, "resolved_recorded_at"),
                result.getString("correlation_id"),
                result.getString("causation_id"),
                result.getString("resolved_deduplication_key"),
                result.getString("payload_json"),
                result.getString("resolved_source_metadata_json"),
                OutboxState.valueOf(result.getString("state")),
                result.getInt("attempt_count"),
                instant(result, "next_attempt_at"),
                instant(result, "published_at"),
                result.getString("last_error"));
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException {

        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void verifyDuplicate(
            OutboxEvent existing,
            String eventType,
            int schemaVersion,
            String entityType,
            String entityId,
            String payloadJson) {

        if (!existing.eventType().equals(eventType)
                || existing.schemaVersion() != schemaVersion
                || !existing.entityType().equals(entityType)
                || !existing.entityId().equals(entityId)
                || !existing.payloadJson().equals(payloadJson)) {
            throw new IllegalStateException(
                    "Deduplication key is already used by a different event: "
                            + existing.deduplicationKey());
        }
    }

    private static String errorMessage(Throwable failure) {
        String message = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            message += ": " + failure.getMessage().trim();
        }
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static String required(
            String value,
            int maximumLength,
            String field) {

        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return limited(normalized, maximumLength, field);
    }

    private static String limitedOptional(
            String value,
            int maximumLength,
            String field) {

        String normalized = optional(value);
        return normalized == null
                ? null
                : limited(normalized, maximumLength, field);
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String limited(
            String value,
            int maximumLength,
            String field) {

        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maximumLength + " characters");
        }
        return value;
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
