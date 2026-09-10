package com.bizlama.signal;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class EventModels {

    private EventModels() {
    }

    public enum AnalyticsType {
        ORDER_INGREDIENT_DEMAND,
        INVENTORY_PURCHASED,
        INVENTORY_CONSUMED,
        WASTE_RECORDED,
        INVENTORY_EXPIRED,
        INVENTORY_REVERSAL,
        INVENTORY_CORRECTED,
        DEMAND_CALCULATION_SNAPSHOT,
        RECOMMENDATION_DECIDED,
        RECOMMENDATION_OUTCOME_RECORDED,
        RAW_ONLY
    }

    public enum Dimension {
        MASS,
        VOLUME,
        COUNT,
        UNKNOWN
    }

    public enum SignalType {
        SHORTAGE_RISK,
        EXPIRY_RISK_SURPLUS,
        MATERIAL_DATA_QUALITY
    }

    public record BrokerEvent(
            String envelopeJson,
            Map<String, String> attributes
    ) implements Serializable {
        public BrokerEvent {
            attributes = Map.copyOf(attributes);
        }

        public static BrokerEvent replay(String envelopeJson) {
            return new BrokerEvent(envelopeJson, Map.of());
        }
    }

    public record CanonicalEvent(
            int schemaVersion,
            String eventId,
            String eventType,
            String kitchenId,
            String locationId,
            String entityType,
            String entityId,
            long occurredAtMillis,
            long recordedAtMillis,
            String correlationId,
            String causationId,
            String deduplicationKey,
            String payloadJson,
            String sourceMetadataJson,
            String state,
            int attemptCount,
            Long nextAttemptAtMillis,
            Long publishedAtMillis,
            String lastError,
            AnalyticsType analyticsType,
            String ingredientId,
            BigDecimal sourceQuantity,
            String sourceUnit,
            BigDecimal canonicalQuantity,
            String canonicalUnit,
            Dimension dimension,
            BigDecimal snapshotGrossDemand,
            BigDecimal snapshotUsableSupply,
            BigDecimal snapshotSafetyStock,
            BigDecimal snapshotShortage,
            BigDecimal snapshotExpiryRiskSurplus,
            boolean stockoutOutcome,
            List<String> qualityFlags,
            String rawJson
    ) implements Serializable {
        public CanonicalEvent {
            qualityFlags = List.copyOf(qualityFlags);
        }

        public long eventTimeMillis() {
            return occurredAtMillis;
        }
    }

    public record ErrorRecord(
            String reasonCode,
            String reason,
            String originalEventId,
            String rawJson,
            long eventTimeMillis,
            long observedAtMillis
    ) implements Serializable {
    }

    public record ParseOutcome(
            CanonicalEvent event,
            ErrorRecord error,
            boolean retainRaw,
            boolean process
    ) implements Serializable {
        static ParseOutcome invalid(
                String code,
                String reason,
                String eventId,
                String raw,
                long eventTimeMillis
        ) {
            return new ParseOutcome(
                    null,
                    new ErrorRecord(code, reason, eventId, raw, eventTimeMillis,
                            System.currentTimeMillis()),
                    false,
                    false
            );
        }

        static ParseOutcome analyticsError(
                CanonicalEvent event,
                String code,
                String reason
        ) {
            return new ParseOutcome(
                    event,
                    new ErrorRecord(
                            code, reason, event.eventId(), event.rawJson(),
                            event.occurredAtMillis(), System.currentTimeMillis()),
                    true,
                    false
            );
        }

        static ParseOutcome late(CanonicalEvent event, String reason) {
            return analyticsError(event, "EVENT_BEYOND_ALLOWED_LATENESS", reason);
        }

        static ParseOutcome rawOnly(CanonicalEvent event) {
            return new ParseOutcome(event, null, true, false);
        }

        static ParseOutcome valid(CanonicalEvent event) {
            return new ParseOutcome(event, null, true, true);
        }
    }

    public record FactRecord(
            int schemaVersion,
            String eventId,
            String eventType,
            String kitchenId,
            String locationId,
            String entityType,
            String entityId,
            long occurredAtMillis,
            long recordedAtMillis,
            String correlationId,
            String causationId,
            String deduplicationKey,
            String payloadJson,
            String sourceMetadataJson,
            String ingredientId,
            BigDecimal sourceQuantity,
            String sourceUnit,
            BigDecimal canonicalQuantity,
            String canonicalUnit,
            String dimension,
            BigDecimal snapshotGrossDemand,
            BigDecimal snapshotUsableSupply,
            BigDecimal snapshotSafetyStock,
            BigDecimal snapshotShortage,
            BigDecimal snapshotExpiryRiskSurplus,
            List<String> qualityFlags
    ) implements Serializable {
    }

    public record FeatureMetrics(
            BigDecimal demand,
            BigDecimal receipts,
            BigDecimal consumption,
            BigDecimal waste,
            BigDecimal expired,
            BigDecimal reversals,
            BigDecimal corrections,
            BigDecimal snapshotGrossDemand,
            BigDecimal snapshotUsableSupply,
            BigDecimal snapshotSafetyStock,
            BigDecimal snapshotShortage,
            BigDecimal snapshotExpiryRiskSurplus,
            long snapshotEventTime,
            long stockouts,
            long recommendationDecisions,
            long recommendationOutcomes,
            long dataQualityEvents,
            long eventCount,
            String canonicalUnit
    ) implements Serializable {
    }

    public record FeatureRecord(
            String windowName,
            long windowStartMillis,
            long windowEndMillis,
            long paneIndex,
            String paneTiming,
            boolean finalPane,
            String kitchenId,
            String locationId,
            String ingredientId,
            BigDecimal demand,
            BigDecimal receipts,
            BigDecimal consumption,
            BigDecimal waste,
            BigDecimal expired,
            BigDecimal reversals,
            BigDecimal corrections,
            BigDecimal snapshotGrossDemand,
            BigDecimal snapshotUsableSupply,
            BigDecimal snapshotSafetyStock,
            BigDecimal snapshotShortage,
            BigDecimal snapshotExpiryRiskSurplus,
            long stockouts,
            long recommendationDecisions,
            long recommendationOutcomes,
            long dataQualityEvents,
            long eventCount,
            String canonicalUnit
    ) implements Serializable {
    }

    public record ProposalCommand(
            String proposalId,
            int schemaVersion,
            String commandType,
            SignalType signalType,
            String riskTier,
            String kitchenId,
            String locationId,
            String ingredientId,
            String windowName,
            long windowStartMillis,
            long windowEndMillis,
            BigDecimal proposedQuantity,
            String canonicalUnit,
            String reasonCode,
            String evidenceJson,
            String targetQueue,
            boolean directMutationAllowed
    ) implements Serializable {
    }
}
