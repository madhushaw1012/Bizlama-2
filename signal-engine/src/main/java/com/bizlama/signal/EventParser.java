package com.bizlama.signal;

import com.bizlama.signal.EventModels.AnalyticsType;
import com.bizlama.signal.EventModels.BrokerEvent;
import com.bizlama.signal.EventModels.CanonicalEvent;
import com.bizlama.signal.EventModels.Dimension;
import com.bizlama.signal.EventModels.ParseOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EventParser implements Serializable {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;
    private static final Set<String> OUTBOX_STATES = Set.of(
            "PENDING", "IN_FLIGHT", "PUBLISHED", "DEAD_LETTER");
    private static final Set<String> RECOMMENDATION_OUTCOMES = Set.of(
            "PREPARED", "SOLD", "FULFILLED", "WASTED",
            "EMERGENCY_PURCHASED", "STOCKOUT", "OVERRIDE",
            "CORRECTED_INVENTORY");
    private static final Map<String, UnitDefinition> UNITS = Map.ofEntries(
            Map.entry("mg", new UnitDefinition("g", Dimension.MASS, "0.001")),
            Map.entry("g", new UnitDefinition("g", Dimension.MASS, "1")),
            Map.entry("kg", new UnitDefinition("g", Dimension.MASS, "1000")),
            Map.entry("ml", new UnitDefinition("ml", Dimension.VOLUME, "1")),
            Map.entry("l", new UnitDefinition("ml", Dimension.VOLUME, "1000")),
            Map.entry("each", new UnitDefinition("each", Dimension.COUNT, "1")),
            Map.entry("piece", new UnitDefinition("each", Dimension.COUNT, "1")),
            Map.entry("pieces", new UnitDefinition("each", Dimension.COUNT, "1")),
            Map.entry("pc", new UnitDefinition("each", Dimension.COUNT, "1")),
            Map.entry("pcs", new UnitDefinition("each", Dimension.COUNT, "1"))
    );

    public ParseOutcome parse(String rawJson, Duration allowedLateness) {
        return parse(BrokerEvent.replay(rawJson), allowedLateness, false);
    }

    public ParseOutcome parse(
            BrokerEvent brokerEvent,
            Duration allowedLateness,
            boolean requireBrokerAttributes
    ) {
        ParseOutcome result = parseBody(
                brokerEvent.envelopeJson(), allowedLateness);
        if (!requireBrokerAttributes || result.event() == null) {
            return result;
        }
        CanonicalEvent event = result.event();
        JsonNode body;
        try {
            body = new ObjectMapper().readTree(event.rawJson());
        } catch (Exception impossible) {
            return result;
        }
        Map<String, String> expected = Map.of(
                "eventId", event.eventId(),
                "eventType", event.eventType(),
                "schemaVersion", Integer.toString(event.schemaVersion()),
                "kitchenId", event.kitchenId(),
                "entityType", event.entityType(),
                "entityId", event.entityId(),
                "occurredAt", body.path("occurredAt").asText());
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(
                    brokerEvent.attributes().get(entry.getKey()))) {
                return ParseOutcome.analyticsError(event,
                        "BROKER_ATTRIBUTE_MISMATCH",
                        "Pub/Sub attribute " + entry.getKey()
                                + " does not match the canonical envelope body.");
            }
        }
        return result;
    }

    private ParseOutcome parseBody(String rawJson, Duration allowedLateness) {
        if (rawJson == null || rawJson.isBlank()) {
            return ParseOutcome.invalid("MALFORMED_ENVELOPE",
                    "Envelope must not be empty.", null, rawJson, 0);
        }
        if (allowedLateness == null || allowedLateness.isNegative()
                || allowedLateness.isZero()) {
            throw new IllegalArgumentException("Allowed lateness must be positive.");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception error) {
            return ParseOutcome.invalid("MALFORMED_ENVELOPE",
                    "Envelope is not valid JSON.", bestEffortEventId(rawJson),
                    rawJson, 0);
        }
        if (root == null || !root.isObject()) {
            return ParseOutcome.invalid("MALFORMED_ENVELOPE",
                    "Envelope must be a JSON object.", null, rawJson, 0);
        }

        String eventId = optionalText(root, "eventId");
        try {
            int schemaVersion = requiredNonNegativeInteger(root, "schemaVersion");
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                return ParseOutcome.invalid("UNSUPPORTED_SCHEMA_VERSION",
                        "Only domain envelope schema version 1 is supported.",
                        eventId, rawJson, 0);
            }
            String eventType = requiredText(root, "eventType");
            String kitchenId = requiredText(root, "kitchenId");
            String entityType = requiredText(root, "entityType");
            String entityId = requiredText(root, "entityId");
            String deduplicationKey = requiredText(root, "deduplicationKey");
            Instant occurredAt = requiredInstant(root, "occurredAt");
            Instant recordedAt = requiredInstant(root, "recordedAt");
            String correlationId = nullableText(root, "correlationId");
            String causationId = nullableText(root, "causationId");
            String payloadJson = requiredText(root, "payloadJson");
            String sourceMetadataJson = requiredText(root, "sourceMetadataJson");
            JsonNode payload = strictEmbeddedJson(mapper, payloadJson, "payloadJson");
            JsonNode sourceMetadata = strictEmbeddedJson(
                    mapper, sourceMetadataJson, "sourceMetadataJson");
            String state = requiredText(root, "state");
            if (!OUTBOX_STATES.contains(state)) {
                throw new EnvelopeException("state is not a recognized outbox state");
            }
            int attemptCount = requiredNonNegativeInteger(root, "attemptCount");
            Instant nextAttemptAt = nullableInstant(root, "nextAttemptAt");
            Instant publishedAt = nullableInstant(root, "publishedAt");
            String lastError = nullableText(root, "lastError");
            if (occurredAt.isAfter(recordedAt.plus(MAX_FUTURE_SKEW))) {
                throw new EnvelopeException(
                        "occurredAt exceeds the five-minute clock-skew policy");
            }

            AnalyticsType analyticsType = analyticsType(eventType);
            List<String> flags = new ArrayList<>();
            if (occurredAt.isAfter(recordedAt)) {
                flags.add("CLOCK_SKEW_WITHIN_TOLERANCE");
            }
            CanonicalEvent rawEvent = new CanonicalEvent(
                    schemaVersion, requiredText(root, "eventId"), eventType,
                    kitchenId, null, entityType, entityId,
                    occurredAt.toEpochMilli(), recordedAt.toEpochMilli(),
                    correlationId, causationId, deduplicationKey,
                    payloadJson, sourceMetadataJson, state, attemptCount,
                    millis(nextAttemptAt), millis(publishedAt), lastError,
                    analyticsType, null, null, null, null, null,
                    Dimension.UNKNOWN, null, null, null, null, null, false,
                    flags, rawJson);
            AnalyticsPayload analytics;
            try {
                analytics = analyticsPayload(
                        analyticsType, payload, sourceMetadata, entityId, flags);
            } catch (EnvelopeException error) {
                return ParseOutcome.analyticsError(rawEvent,
                        "INVALID_ANALYTICS_PAYLOAD", error.getMessage());
            }
            CanonicalEvent event = new CanonicalEvent(
                    schemaVersion, requiredText(root, "eventId"), eventType,
                    kitchenId, analytics.locationId(), entityType, entityId,
                    occurredAt.toEpochMilli(), recordedAt.toEpochMilli(),
                    correlationId, causationId, deduplicationKey,
                    payloadJson, sourceMetadataJson, state, attemptCount,
                    millis(nextAttemptAt), millis(publishedAt), lastError,
                    analyticsType, analytics.ingredientId(),
                    analytics.sourceQuantity(), analytics.sourceUnit(),
                    analytics.canonicalQuantity(), analytics.canonicalUnit(),
                    analytics.dimension(), analytics.snapshotGrossDemand(),
                    analytics.snapshotUsableSupply(),
                    analytics.snapshotSafetyStock(),
                    analytics.snapshotShortage(),
                    analytics.snapshotExpiryRiskSurplus(),
                    analytics.stockoutOutcome(), flags, rawJson);

            if (analyticsType == AnalyticsType.RAW_ONLY
                    || analytics.ingredientId() == null) {
                return ParseOutcome.rawOnly(event);
            }
            return ParseOutcome.valid(event);
        } catch (EnvelopeException | java.time.DateTimeException error) {
            return ParseOutcome.invalid("INVALID_ENVELOPE", error.getMessage(),
                    eventId, rawJson, 0);
        }
    }

    private AnalyticsPayload analyticsPayload(
            AnalyticsType type,
            JsonNode payload,
            JsonNode sourceMetadata,
            String entityId,
            List<String> flags
    ) {
        if (type == AnalyticsType.RAW_ONLY) {
            return AnalyticsPayload.rawOnly();
        }
        if (!payload.isObject()) {
            throw new EnvelopeException("Supported analytics payload must be a JSON object");
        }
        String locationId = optionalText(payload, "locationId");
        if (locationId == null && sourceMetadata.isObject()) {
            locationId = optionalText(sourceMetadata, "locationId");
        }
        if (locationId == null) {
            throw new EnvelopeException(
                    "locationId is required for supported analytics events");
        }
        String ingredientId = optionalText(payload, "ingredientId");
        if (ingredientId == null && isInventory(type)) {
            ingredientId = entityId;
        }
        if (type == AnalyticsType.RECOMMENDATION_DECIDED) {
            String unit = optionalText(payload, "unit");
            UnitDefinition definition = unitDefinition(unit, flags);
            return new AnalyticsPayload(locationId, ingredientId,
                    null, unit, null,
                    definition == null ? null : definition.canonicalUnit(),
                    definition == null ? Dimension.UNKNOWN : definition.dimension(),
                    null, null, null, null, null, false);
        }
        if (type == AnalyticsType.RECOMMENDATION_OUTCOME_RECORDED) {
            String outcomeType = requiredText(payload, "outcomeType");
            if (!RECOMMENDATION_OUTCOMES.contains(outcomeType)) {
                throw new EnvelopeException(
                        "outcomeType is not a recognized recommendation outcome");
            }
            String unit = optionalText(payload, "unit");
            UnitDefinition definition = unitDefinition(unit, flags);
            return new AnalyticsPayload(locationId, ingredientId,
                    null, unit, null,
                    definition == null ? null : definition.canonicalUnit(),
                    definition == null ? Dimension.UNKNOWN : definition.dimension(),
                    null, null, null, null, null,
                    "STOCKOUT".equals(outcomeType));
        }

        String quantityField = type == AnalyticsType.ORDER_INGREDIENT_DEMAND
                ? "canonicalDemand" : "quantity";
        String unitField = type == AnalyticsType.ORDER_INGREDIENT_DEMAND
                || type == AnalyticsType.DEMAND_CALCULATION_SNAPSHOT
                ? "canonicalUnit" : "unit";
        String unit = optionalText(payload, unitField);
        UnitDefinition definition = unitDefinition(unit, flags);

        if (type == AnalyticsType.DEMAND_CALCULATION_SNAPSHOT) {
            BigDecimal gross = requiredPositiveOrZeroDecimal(payload, "grossDemand");
            BigDecimal usable = requiredPositiveOrZeroDecimal(payload, "usableSupply");
            BigDecimal safety = requiredPositiveOrZeroDecimal(payload, "safetyStock");
            BigDecimal shortage = requiredPositiveOrZeroDecimal(payload, "shortage");
            BigDecimal expiry = requiredPositiveOrZeroDecimal(
                    payload, "expiryRiskSurplus");
            return new AnalyticsPayload(locationId, ingredientId,
                    null, unit, null,
                    definition == null ? null : definition.canonicalUnit(),
                    definition == null ? Dimension.UNKNOWN : definition.dimension(),
                    canonical(gross, definition), canonical(usable, definition),
                    canonical(safety, definition), canonical(shortage, definition),
                    canonical(expiry, definition), false);
        }

        BigDecimal sourceQuantity = requiredPositiveDecimal(payload, quantityField);
        return new AnalyticsPayload(
                locationId, ingredientId, sourceQuantity, unit,
                canonical(sourceQuantity, definition),
                definition == null ? null : definition.canonicalUnit(),
                definition == null ? Dimension.UNKNOWN : definition.dimension(),
                null, null, null, null, null, false);
    }

    private static boolean isInventory(AnalyticsType type) {
        return switch (type) {
            case INVENTORY_PURCHASED, INVENTORY_CONSUMED, WASTE_RECORDED,
                    INVENTORY_EXPIRED, INVENTORY_REVERSAL, INVENTORY_CORRECTED -> true;
            default -> false;
        };
    }

    private static AnalyticsType analyticsType(String eventType) {
        try {
            return AnalyticsType.valueOf(eventType);
        } catch (IllegalArgumentException ignored) {
            return AnalyticsType.RAW_ONLY;
        }
    }

    private static UnitDefinition unitDefinition(String raw, List<String> flags) {
        if (raw == null) {
            flags.add("MISSING_UNIT");
            return null;
        }
        UnitDefinition result = UNITS.get(raw.trim().toLowerCase(Locale.ROOT));
        if (result == null) {
            flags.add("UNSUPPORTED_UNIT");
        }
        return result;
    }

    private static BigDecimal canonical(BigDecimal value, UnitDefinition definition) {
        return definition == null ? null : value.multiply(
                definition.multiplier(), CALCULATION_CONTEXT).stripTrailingZeros();
    }

    private static BigDecimal requiredPositiveDecimal(JsonNode node, String field) {
        BigDecimal value = requiredDecimal(node, field);
        if (value.signum() <= 0) {
            throw new EnvelopeException(field + " must be positive");
        }
        return value;
    }

    private static BigDecimal requiredPositiveOrZeroDecimal(JsonNode node, String field) {
        BigDecimal value = requiredDecimal(node, field);
        if (value.signum() < 0) {
            throw new EnvelopeException(field + " must not be negative");
        }
        return value;
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isNumber() && !value.isTextual()) {
            throw new EnvelopeException(field + " must be an exact decimal");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException error) {
            throw new EnvelopeException(field + " must be an exact decimal");
        }
    }

    private static JsonNode strictEmbeddedJson(
            ObjectMapper mapper, String value, String field) {
        try {
            JsonNode parsed = mapper.readTree(value);
            if (parsed == null) {
                throw new EnvelopeException(field + " must contain JSON");
            }
            return parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new EnvelopeException(field + " must contain valid JSON");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new EnvelopeException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : null;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new EnvelopeException(field + " must be a string or null");
        }
        return value.textValue();
    }

    private static int requiredNonNegativeInteger(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0) {
            throw new EnvelopeException(field + " must be a non-negative integer");
        }
        return value.intValue();
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        if (!value.isTextual()) {
            throw new EnvelopeException(field + " must be an ISO-8601 instant");
        }
        return parseInstant(value.textValue(), field);
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        JsonNode value = requiredField(node, field);
        return value.isNull() ? null : parseInstant(value.asText(), field);
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (java.time.DateTimeException error) {
            throw new EnvelopeException(field + " must be an ISO-8601 UTC instant");
        }
    }

    private static JsonNode requiredField(JsonNode node, String field) {
        if (!node.has(field)) {
            throw new EnvelopeException(field + " must be present");
        }
        return node.get(field);
    }

    private static Long millis(Instant value) {
        return value == null ? null : value.toEpochMilli();
    }

    private static String bestEffortEventId(String raw) {
        int marker = raw.indexOf("\"eventId\"");
        int colon = marker < 0 ? -1 : raw.indexOf(":", marker);
        int firstQuote = colon < 0 ? -1 : raw.indexOf("\"", colon);
        int secondQuote = firstQuote < 0 ? -1 : raw.indexOf("\"", firstQuote + 1);
        return firstQuote < 0 || secondQuote < 0
                ? null : raw.substring(firstQuote + 1, secondQuote);
    }

    private record UnitDefinition(
            String canonicalUnit,
            Dimension dimension,
            BigDecimal multiplier
    ) implements Serializable {
        UnitDefinition(String unit, Dimension dimension, String multiplier) {
            this(unit, dimension, new BigDecimal(multiplier));
        }
    }

    private record AnalyticsPayload(
            String locationId,
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
            boolean stockoutOutcome
    ) {
        static AnalyticsPayload rawOnly() {
            return new AnalyticsPayload(null, null, null, null, null, null,
                    Dimension.UNKNOWN, null, null, null, null, null, false);
        }
    }

    private static final class EnvelopeException extends RuntimeException {
        private EnvelopeException(String message) {
            super(message);
        }
    }

}
