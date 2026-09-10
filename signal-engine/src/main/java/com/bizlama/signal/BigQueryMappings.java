package com.bizlama.signal;

import com.bizlama.signal.EventModels.CanonicalEvent;
import com.bizlama.signal.EventModels.ErrorRecord;
import com.bizlama.signal.EventModels.FactRecord;
import com.bizlama.signal.EventModels.FeatureRecord;
import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BigQueryMappings {

    private BigQueryMappings() {
    }

    public static TableSchema rawSchema() {
        return schema(
                field("event_id", "STRING", "REQUIRED"),
                field("event_type", "STRING", "REQUIRED"),
                field("schema_version", "INTEGER", "REQUIRED"),
                field("kitchen_id", "STRING", "REQUIRED"),
                field("location_id", "STRING", "NULLABLE"),
                field("entity_type", "STRING", "REQUIRED"),
                field("entity_id", "STRING", "REQUIRED"),
                field("occurred_at", "TIMESTAMP", "REQUIRED"),
                field("recorded_at", "TIMESTAMP", "REQUIRED"),
                field("correlation_id", "STRING", "NULLABLE"),
                field("causation_id", "STRING", "NULLABLE"),
                field("deduplication_key", "STRING", "REQUIRED"),
                field("payload_json", "JSON", "REQUIRED"),
                field("source_metadata_json", "JSON", "REQUIRED"),
                field("outbox_state", "STRING", "REQUIRED"),
                field("attempt_count", "INTEGER", "REQUIRED"),
                field("next_attempt_at", "TIMESTAMP", "NULLABLE"),
                field("published_at", "TIMESTAMP", "NULLABLE"),
                field("last_error", "STRING", "NULLABLE"),
                field("analytics_type", "STRING", "REQUIRED"),
                field("ingredient_id", "STRING", "NULLABLE"),
                field("source_quantity", "NUMERIC", "NULLABLE"),
                field("source_unit", "STRING", "NULLABLE"),
                field("canonical_quantity", "NUMERIC", "NULLABLE"),
                field("canonical_unit", "STRING", "NULLABLE"),
                field("dimension", "STRING", "REQUIRED"),
                field("snapshot_gross_demand", "NUMERIC", "NULLABLE"),
                field("snapshot_usable_supply", "NUMERIC", "NULLABLE"),
                field("snapshot_safety_stock", "NUMERIC", "NULLABLE"),
                field("snapshot_shortage", "NUMERIC", "NULLABLE"),
                field("snapshot_expiry_risk_surplus", "NUMERIC", "NULLABLE"),
                repeatedField("quality_flags", "STRING"),
                field("envelope_json", "JSON", "REQUIRED")
        );
    }

    public static TableSchema factSchema() {
        return schema(
                field("schema_version", "INTEGER", "REQUIRED"),
                field("event_id", "STRING", "REQUIRED"),
                field("event_type", "STRING", "REQUIRED"),
                field("kitchen_id", "STRING", "REQUIRED"),
                field("location_id", "STRING", "REQUIRED"),
                field("entity_type", "STRING", "REQUIRED"),
                field("entity_id", "STRING", "REQUIRED"),
                field("occurred_at", "TIMESTAMP", "REQUIRED"),
                field("recorded_at", "TIMESTAMP", "REQUIRED"),
                field("correlation_id", "STRING", "NULLABLE"),
                field("causation_id", "STRING", "NULLABLE"),
                field("deduplication_key", "STRING", "REQUIRED"),
                field("payload_json", "JSON", "REQUIRED"),
                field("source_metadata_json", "JSON", "REQUIRED"),
                field("ingredient_id", "STRING", "REQUIRED"),
                field("source_quantity", "NUMERIC", "NULLABLE"),
                field("source_unit", "STRING", "NULLABLE"),
                field("canonical_quantity", "NUMERIC", "NULLABLE"),
                field("canonical_unit", "STRING", "NULLABLE"),
                field("dimension", "STRING", "REQUIRED"),
                field("snapshot_gross_demand", "NUMERIC", "NULLABLE"),
                field("snapshot_usable_supply", "NUMERIC", "NULLABLE"),
                field("snapshot_safety_stock", "NUMERIC", "NULLABLE"),
                field("snapshot_shortage", "NUMERIC", "NULLABLE"),
                field("snapshot_expiry_risk_surplus", "NUMERIC", "NULLABLE"),
                repeatedField("quality_flags", "STRING")
        );
    }

    public static TableSchema featureSchema() {
        return schema(
                field("window_name", "STRING", "REQUIRED"),
                field("window_start", "TIMESTAMP", "REQUIRED"),
                field("window_end", "TIMESTAMP", "REQUIRED"),
                field("pane_index", "INTEGER", "REQUIRED"),
                field("pane_timing", "STRING", "REQUIRED"),
                field("final_pane", "BOOLEAN", "REQUIRED"),
                field("kitchen_id", "STRING", "REQUIRED"),
                field("location_id", "STRING", "REQUIRED"),
                field("ingredient_id", "STRING", "REQUIRED"),
                field("demand", "NUMERIC", "REQUIRED"),
                field("receipts", "NUMERIC", "REQUIRED"),
                field("consumption", "NUMERIC", "REQUIRED"),
                field("waste", "NUMERIC", "REQUIRED"),
                field("expired", "NUMERIC", "REQUIRED"),
                field("reversals", "NUMERIC", "REQUIRED"),
                field("corrections", "NUMERIC", "REQUIRED"),
                field("snapshot_gross_demand", "NUMERIC", "NULLABLE"),
                field("snapshot_usable_supply", "NUMERIC", "NULLABLE"),
                field("snapshot_safety_stock", "NUMERIC", "NULLABLE"),
                field("snapshot_shortage", "NUMERIC", "NULLABLE"),
                field("snapshot_expiry_risk_surplus", "NUMERIC", "NULLABLE"),
                field("stockouts", "INTEGER", "REQUIRED"),
                field("recommendation_decisions", "INTEGER", "REQUIRED"),
                field("recommendation_outcomes", "INTEGER", "REQUIRED"),
                field("data_quality_events", "INTEGER", "REQUIRED"),
                field("event_count", "INTEGER", "REQUIRED"),
                field("canonical_unit", "STRING", "NULLABLE")
        );
    }

    public static TableSchema errorSchema() {
        return schema(
                field("reason_code", "STRING", "REQUIRED"),
                field("reason", "STRING", "REQUIRED"),
                field("original_event_id", "STRING", "NULLABLE"),
                field("event_time", "TIMESTAMP", "NULLABLE"),
                field("observed_at", "TIMESTAMP", "REQUIRED"),
                field("raw_json", "STRING", "NULLABLE")
        );
    }

    public static TableRow rawRow(CanonicalEvent event) {
        return identityRow(event.schemaVersion(), event.eventId(), event.eventType(),
                event.kitchenId(), event.locationId(),
                event.entityType(), event.entityId(),
                event.occurredAtMillis(), event.recordedAtMillis(),
                event.correlationId(), event.causationId(),
                event.deduplicationKey(), event.payloadJson(),
                event.sourceMetadataJson())
                .set("outbox_state", event.state())
                .set("attempt_count", event.attemptCount())
                .set("next_attempt_at", timestamp(event.nextAttemptAtMillis()))
                .set("published_at", timestamp(event.publishedAtMillis()))
                .set("last_error", event.lastError())
                .set("analytics_type", event.analyticsType().name())
                .set("ingredient_id", event.ingredientId())
                .set("source_quantity", decimal(event.sourceQuantity()))
                .set("source_unit", event.sourceUnit())
                .set("canonical_quantity", decimal(event.canonicalQuantity()))
                .set("canonical_unit", event.canonicalUnit())
                .set("dimension", event.dimension().name())
                .set("snapshot_gross_demand", decimal(event.snapshotGrossDemand()))
                .set("snapshot_usable_supply", decimal(event.snapshotUsableSupply()))
                .set("snapshot_safety_stock", decimal(event.snapshotSafetyStock()))
                .set("snapshot_shortage", decimal(event.snapshotShortage()))
                .set("snapshot_expiry_risk_surplus",
                        decimal(event.snapshotExpiryRiskSurplus()))
                .set("quality_flags", event.qualityFlags())
                .set("envelope_json", event.rawJson());
    }

    public static TableRow factRow(FactRecord fact) {
        return identityRow(fact.schemaVersion(), fact.eventId(), fact.eventType(),
                fact.kitchenId(), fact.locationId(),
                fact.entityType(), fact.entityId(),
                fact.occurredAtMillis(), fact.recordedAtMillis(),
                fact.correlationId(), fact.causationId(), fact.deduplicationKey(),
                fact.payloadJson(), fact.sourceMetadataJson())
                .set("ingredient_id", fact.ingredientId())
                .set("source_quantity", decimal(fact.sourceQuantity()))
                .set("source_unit", fact.sourceUnit())
                .set("canonical_quantity", decimal(fact.canonicalQuantity()))
                .set("canonical_unit", fact.canonicalUnit())
                .set("dimension", fact.dimension())
                .set("snapshot_gross_demand", decimal(fact.snapshotGrossDemand()))
                .set("snapshot_usable_supply", decimal(fact.snapshotUsableSupply()))
                .set("snapshot_safety_stock", decimal(fact.snapshotSafetyStock()))
                .set("snapshot_shortage", decimal(fact.snapshotShortage()))
                .set("snapshot_expiry_risk_surplus",
                        decimal(fact.snapshotExpiryRiskSurplus()))
                .set("quality_flags", fact.qualityFlags());
    }

    private static TableRow identityRow(int version, String eventId,
            String eventType, String kitchenId, String locationId,
            String entityType, String entityId, long occurredAt, long recordedAt,
            String correlationId, String causationId, String deduplicationKey,
            String payloadJson, String sourceMetadataJson) {
        return new TableRow().set("schema_version", version)
                .set("event_id", eventId).set("event_type", eventType)
                .set("kitchen_id", kitchenId).set("location_id", locationId)
                .set("entity_type", entityType)
                .set("entity_id", entityId)
                .set("occurred_at", timestamp(occurredAt))
                .set("recorded_at", timestamp(recordedAt))
                .set("correlation_id", correlationId)
                .set("causation_id", causationId)
                .set("deduplication_key", deduplicationKey)
                .set("payload_json", payloadJson)
                .set("source_metadata_json", sourceMetadataJson);
    }

    public static TableRow featureRow(FeatureRecord feature) {
        return new TableRow().set("window_name", feature.windowName())
                .set("window_start", timestamp(feature.windowStartMillis()))
                .set("window_end", timestamp(feature.windowEndMillis()))
                .set("pane_index", feature.paneIndex())
                .set("pane_timing", feature.paneTiming())
                .set("final_pane", feature.finalPane())
                .set("kitchen_id", feature.kitchenId())
                .set("location_id", feature.locationId())
                .set("ingredient_id", feature.ingredientId())
                .set("demand", decimal(feature.demand()))
                .set("receipts", decimal(feature.receipts()))
                .set("consumption", decimal(feature.consumption()))
                .set("waste", decimal(feature.waste()))
                .set("expired", decimal(feature.expired()))
                .set("reversals", decimal(feature.reversals()))
                .set("corrections", decimal(feature.corrections()))
                .set("snapshot_gross_demand", decimal(feature.snapshotGrossDemand()))
                .set("snapshot_usable_supply", decimal(feature.snapshotUsableSupply()))
                .set("snapshot_safety_stock", decimal(feature.snapshotSafetyStock()))
                .set("snapshot_shortage", decimal(feature.snapshotShortage()))
                .set("snapshot_expiry_risk_surplus",
                        decimal(feature.snapshotExpiryRiskSurplus()))
                .set("stockouts", feature.stockouts())
                .set("recommendation_decisions", feature.recommendationDecisions())
                .set("recommendation_outcomes", feature.recommendationOutcomes())
                .set("data_quality_events", feature.dataQualityEvents())
                .set("event_count", feature.eventCount())
                .set("canonical_unit", feature.canonicalUnit());
    }

    public static TableRow errorRow(ErrorRecord error) {
        return new TableRow().set("reason_code", error.reasonCode())
                .set("reason", error.reason())
                .set("original_event_id", error.originalEventId())
                .set("event_time", error.eventTimeMillis() == 0
                        ? null : timestamp(error.eventTimeMillis()))
                .set("observed_at", timestamp(error.observedAtMillis()))
                .set("raw_json", error.rawJson());
    }

    private static String timestamp(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis).toString();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static TableSchema schema(TableFieldSchema... fields) {
        return new TableSchema().setFields(List.of(fields));
    }

    private static TableFieldSchema field(String name, String type, String mode) {
        return new TableFieldSchema().setName(name).setType(type).setMode(mode);
    }

    private static TableFieldSchema repeatedField(String name, String type) {
        return field(name, type, "REPEATED");
    }
}
