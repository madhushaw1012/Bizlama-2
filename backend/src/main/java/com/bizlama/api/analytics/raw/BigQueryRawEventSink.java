package com.bizlama.api.analytics.raw;

import com.bizlama.api.outbox.CanonicalJson;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.TableId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes the complete envelope to the raw table outside operational database
 * transactions.
 *
 * <p>The stable BigQuery insert ID supplies best-effort retry deduplication,
 * as defined by the BigQuery streaming insert API. BigQuery does not enforce a
 * primary key, so {@code event_id} and {@code envelope_json} are retained for
 * downstream uniqueness assertions and deterministic compaction.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.analytics.raw",
        name = "enabled",
        havingValue = "true")
public class BigQueryRawEventSink implements IdempotentRawEventSink {

    private final BigQuery bigQuery;
    private final TableId tableId;
    private final CanonicalJson canonicalJson;

    public BigQueryRawEventSink(
            BigQuery bigQuery,
            RawAnalyticsProperties properties,
            CanonicalJson canonicalJson
    ) {
        this.bigQuery = bigQuery;
        this.canonicalJson = canonicalJson;
        this.tableId = TableId.of(
                properties.requiredProjectId(),
                properties.requiredDataset(),
                properties.requiredTable());
    }

    @Override
    public void insertIfAbsent(
            RawEventEnvelope event,
            RawEventDelivery delivery
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", event.eventId());
        row.put("event_type", event.eventType());
        row.put("schema_version", event.schemaVersion());
        row.put("kitchen_id", event.kitchenId());
        row.put("entity_type", event.entityType());
        row.put("entity_id", event.entityId());
        row.put("occurred_at", timestamp(event.occurredAt()));
        row.put("recorded_at", timestamp(event.recordedAt()));
        row.put("correlation_id", event.correlationId());
        row.put("causation_id", event.causationId());
        row.put("deduplication_key", event.deduplicationKey());
        row.put("payload_json", event.payloadJson());
        row.put("source_metadata_json", event.sourceMetadataJson());
        row.put("outbox_state", event.state());
        row.put("attempt_count", event.attemptCount());
        row.put("next_attempt_at", timestamp(event.nextAttemptAt()));
        row.put("published_at", timestamp(event.publishedAt()));
        row.put("last_error", event.lastError());
        row.put(
                "pubsub_attributes",
                canonicalJson.write(delivery.pubsubAttributes()));
        row.put("pubsub_message_id", delivery.messageId());
        row.put("pubsub_publish_time", timestamp(delivery.publishTime()));
        row.put("ingested_at", timestamp(delivery.ingestedAt()));
        row.put("delivery_attempt", delivery.deliveryAttempt());
        row.put("source_subscription", delivery.sourceSubscription());
        row.put("payload_sha256", payloadSha256(event.payloadJson()));
        row.put("is_replay", delivery.replay());
        row.put("envelope_json", event.envelopeJson());

        InsertAllRequest request = InsertAllRequest.newBuilder(tableId)
                .setIgnoreUnknownValues(false)
                .setSkipInvalidRows(false)
                .addRow(event.eventId(), row)
                .build();
        InsertAllResponse response = bigQuery.insertAll(request);
        if (response.hasErrors()) {
            throw new IllegalStateException(
                    "BigQuery rejected raw event "
                            + event.eventId()
                            + ": "
                            + response.getInsertErrors());
        }
    }

    private static String timestamp(Instant value) {
        return value == null ? null : value.toString();
    }

    /**
     * Hashes the exact UTF-8 bytes of payloadJson. TransactionalOutboxService
     * creates that value with CanonicalJson, so this is the fingerprint of the
     * canonical domain payload, independent of delivery metadata and retries.
     */
    static String payloadSha256(String canonicalPayloadJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonicalPayloadJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
